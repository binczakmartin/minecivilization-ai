package ai.minecivilization.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * /mciv — the operator interface into the simulation:
 *   /mciv citizen spawn|list|inspect &lt;citizen&gt;|think &lt;citizen&gt;|stop &lt;citizen&gt;
 *   /mciv ai status|reconnect
 *   /mciv debug on|off
 */
public final class McivCommands {
    private McivCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("mciv")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("citizen")
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx.getSource())))
                        .then(Commands.literal("list")
                                .executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("inspect")
                                .then(citizenArg().executes(McivCommands::inspect)))
                        .then(Commands.literal("think")
                                .then(citizenArg().executes(McivCommands::think)))
                        .then(Commands.literal("stop")
                                .then(citizenArg().executes(McivCommands::stop))))
                .then(Commands.literal("ai")
                        .then(Commands.literal("status")
                                .executes(ctx -> aiStatus(ctx.getSource())))
                        .then(Commands.literal("reconnect")
                                .executes(ctx -> aiReconnect(ctx.getSource()))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(ctx -> debug(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> debug(ctx.getSource(), false)))));
    }

    // ------------------------------------------------------------------ argument

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> citizenArg() {
        return Commands.argument("citizen", StringArgumentType.word())
                .suggests(McivCommands::suggestCitizens);
    }

    private static CompletableFuture<Suggestions> suggestCitizens(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        List<String> names = new ArrayList<>();
        for (CitizenEntity citizen : CitizenIndex.all()) {
            String name = citizen.getIdentity().name;
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CitizenEntity resolve(CommandSourceStack source, CommandContext<CommandSourceStack> ctx) {
        String nameOrId = StringArgumentType.getString(ctx, "citizen");
        for (CitizenEntity citizen : CitizenIndex.all()) {
            if (nameOrId.equalsIgnoreCase(citizen.getIdentity().name)) {
                return citizen;
            }
            var id = citizen.getIdentity().citizenId;
            if (id != null && id.toString().startsWith(nameOrId)) {
                return citizen;
            }
        }
        source.sendFailure(Component.literal(
                "No citizen named '" + nameOrId + "' — try /mciv citizen list"));
        return null;
    }

    // ------------------------------------------------------------------ citizen

    private static int spawn(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        CitizenEntity citizen = ModEntities.CITIZEN.get().create(level);
        if (citizen == null) {
            source.sendFailure(Component.literal("Could not create a citizen entity"));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        citizen.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        citizen.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
        if (!level.addFreshEntity(citizen)) {
            source.sendFailure(Component.literal("Spawn was rejected by the world"));
            return 0;
        }
        String name = citizen.getIdentity().name;
        source.sendSuccess(() -> Component.literal(
                "Citizen '" + name + "' spawned (" + citizen.getIdentity().citizenId + ")."), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<CitizenEntity> citizens = CitizenIndex.all();
        if (citizens.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No citizens in this world."), false);
            return 0;
        }
        for (CitizenEntity citizen : citizens) {
            var id = citizen.getIdentity();
            source.sendSuccess(() -> Component.literal(String.format(
                    "%s [%s] pos=%d,%d,%d hunger=%.0f status=%s goal=%s",
                    id.name, id.profession,
                    (int) citizen.getX(), (int) citizen.getY(), (int) citizen.getZ(),
                    citizen.getHunger(),
                    citizen.getStatusName(),
                    citizen.getCitizenBrain().currentGoal() == null
                            ? "-" : citizen.getCitizenBrain().currentGoal().type.name())), false);
        }
        int count = citizens.size();
        source.sendSuccess(() -> Component.literal(count + " citizen(s)."), false);
        return count;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;

        var brain = citizen.getCitizenBrain();
        var id = citizen.getIdentity();

        source.sendSuccess(() -> Component.literal(
                "=== " + id.name + " [" + id.profession + "] ==="), false);
        source.sendSuccess(() -> Component.literal(
                "citizenId=" + id.citizenId + " pos=" + (int) citizen.getX() + ","
                        + (int) citizen.getY() + "," + (int) citizen.getZ()), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "health=%.1f hunger=%.0f energy=%.0f workAllowed=%s registered=%s",
                citizen.getHealth(), citizen.getHunger(), citizen.getEnergy(),
                citizen.isWorkAllowed(), citizen.isRegisteredWithService())), false);
        source.sendSuccess(() -> Component.literal(
                "brain=" + brain.status()
                        + " goal=" + (brain.currentGoal() == null ? "-" : brain.currentGoal().type.name())
                        + " task=" + (brain.currentTaskOrNull() == null
                                ? "-" : brain.currentTaskOrNull().type.name())
                        + " failures=" + brain.consecutiveTaskFailures()
                        + " decisionPending=" + brain.isDecisionPending()), false);
        source.sendSuccess(() -> Component.literal(
                "skill=" + (brain.currentSkillLabel().isBlank() ? "-" : brain.currentSkillLabel())
                        + " disabled=" + citizen.getDisabledSkills()), false);
        source.sendSuccess(() -> Component.literal(
                "combat=" + (citizen.isInCombat()
                        ? "fighting " + citizen.combatTargetId() : "none")), false);
        source.sendSuccess(() -> Component.literal(
                "inventory=" + citizen.getInventory().summary()), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "skills mining=%.1f farming=%.1f building=%.1f logistics=%.1f",
                citizen.getSkills().mining, citizen.getSkills().farming,
                citizen.getSkills().building, citizen.getSkills().logistics)), false);
        source.sendSuccess(() -> Component.literal("ai bridge: " + AiBridge.statusString()), false);
        StringBuilder events = new StringBuilder("recent: ");
        var it = brain.recentEvents();
        while (it.hasNext()) events.append(it.next()).append(" | ");
        source.sendSuccess(() -> Component.literal(events.toString()), false);
        return 1;
    }

    private static int think(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;
        citizen.setWorkAllowed(true);
        citizen.getCitizenBrain().forceDecision(source.getLevel(), citizen);
        source.sendSuccess(() -> Component.literal(
                citizen.getIdentity().name + " is asking the local AI for a new decision."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;
        citizen.getCitizenBrain().stopAll(citizen);
        citizen.setWorkAllowed(false);
        citizen.getNavigator().stop();
        source.sendSuccess(() -> Component.literal(
                citizen.getIdentity().name + " stopped (work re-enabled by /mciv citizen think)."), true);
        return 1;
    }

    // ------------------------------------------------------------------ ai / debug

    private static int aiStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("AI bridge: " + AiBridge.statusString()), false);
        source.sendSuccess(() -> Component.literal(
                "base=" + ModConfig.aiBaseUrl()
                        + " enabled=" + ModConfig.AI_ENABLED.get()
                        + " pendingMainThreadTasks=" + AiBridge.pendingMainThreadTasks()), false);
        return 1;
    }

    private static int aiReconnect(CommandSourceStack source) {
        AiBridge.reconnect();
        source.sendSuccess(() -> Component.literal("Circuit reset — probing the AI service."), true);
        return 1;
    }

    private static int debug(CommandSourceStack source, boolean on) {
        ModConfig.debugOverride = on;
        source.sendSuccess(() -> Component.literal("Debug logging " + (on ? "ON" : "OFF") + "."), true);
        return 1;
    }
}
