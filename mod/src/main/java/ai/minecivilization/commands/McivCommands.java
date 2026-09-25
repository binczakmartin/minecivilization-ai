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
import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.architecture.HouseCatalog;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
import ai.minecivilization.colony.CitizenMarkers;
import ai.minecivilization.colony.ColonyCensus;
import ai.minecivilization.colony.LandmarkKind;
import ai.minecivilization.colony.LandmarkRegistry;
import ai.minecivilization.colony.ColonyData;
import ai.minecivilization.colony.ColonyLife;
import ai.minecivilization.colony.Population;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.storage.ItemCategory;
import ai.minecivilization.storage.SettlementStock;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
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
 *   /mciv camp
 *   /mciv storage
 *   /mciv colony
 *   /mciv places
 *   /mciv events [count]
 *   /mciv roads
 *   /mciv signs
 *   /mciv work
 *   /mciv map [width]
 *   /mciv track &lt;citizen&gt;|off
 *   /mciv citizen report &lt;citizen&gt;
 *   /mciv citizen rescue &lt;citizen&gt;
 *   /mciv highlight on|off
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
                                .then(citizenArg().executes(McivCommands::stop)))
                        .then(Commands.literal("report")
                                .then(citizenArg().executes(McivCommands::report)))
                        .then(Commands.literal("rescue")
                                .then(citizenArg().executes(McivCommands::rescue))))
                .then(Commands.literal("ai")
                        .then(Commands.literal("status")
                                .executes(ctx -> aiStatus(ctx.getSource())))
                        .then(Commands.literal("reconnect")
                                .executes(ctx -> aiReconnect(ctx.getSource()))))
                .then(Commands.literal("camp")
                        .executes(McivCommands::camp))
                .then(Commands.literal("storage")
                        .executes(McivCommands::storage))
                .then(Commands.literal("colony")
                        .executes(McivCommands::colony))
                .then(Commands.literal("places")
                        .executes(McivCommands::places))
                .then(Commands.literal("events")
                        .executes(ctx -> events(ctx, 20))
                        .then(Commands.argument("count",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> events(ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(ctx, "count")))))
                .then(Commands.literal("roads")
                        .executes(McivCommands::roads))
                .then(Commands.literal("signs")
                        .executes(McivCommands::signs))
                .then(Commands.literal("work")
                        .executes(McivCommands::work))
                .then(Commands.literal("map")
                        .executes(ctx -> map(ctx, 48))
                        .then(Commands.argument("width",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(16, 96))
                                .executes(ctx -> map(ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(ctx, "width")))))
                .then(Commands.literal("track")
                        .then(Commands.literal("off")
                                .executes(McivCommands::trackOff))
                        .then(citizenArg().executes(McivCommands::track)))
                .then(Commands.literal("highlight")
                        .then(Commands.literal("on")
                                .executes(ctx -> highlight(ctx.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> highlight(ctx.getSource(), false))))
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

    // ------------------------------------------------------------------ what the colony knows

    /**
     * Everywhere the colony remembers. Answers "do they know there is a furnace
     * over there?" — which used to be unanswerable, because they did not.
     */
    private static int places(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPos.containing(source.getPosition());
        LandmarkRegistry registry = LandmarkRegistry.get(level);

        var census = registry.census();
        if (census.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "The colony knows of no workshops or machinery yet."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Known places:"), false);
        int total = 0;
        for (LandmarkKind kind : LandmarkKind.values()) {
            Integer count = census.get(kind);
            if (count == null || count == 0) continue;
            total += count;
            BlockPos nearest = registry.nearest(kind, from);
            String where = nearest == null ? "" : String.format("   nearest %dm at %d,%d,%d",
                    (int) Math.sqrt(nearest.distSqr(from)),
                    nearest.getX(), nearest.getY(), nearest.getZ());
            String line = String.format("  %-18s %3d%s", kind.label(), count, where);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        int shown = total;
        source.sendSuccess(() -> Component.literal("  " + shown + " place(s) remembered"), false);
        return shown;
    }

    // ------------------------------------------------------------------ finding people

    /**
     * Outline every citizen through terrain. Name tags work at close range;
     * this is what finds someone at the bottom of a shaft.
     */
    private static int highlight(CommandSourceStack source, boolean enabled) {
        CitizenMarkers.setHighlighted(enabled);
        int count = CitizenIndex.population();
        source.sendSuccess(() -> Component.literal(enabled
                ? "Citizens are now outlined through walls (" + count + ")."
                : "Citizen outlines off."), true);
        return count;
    }

    // ------------------------------------------------------------------ colony

    /**
     * The settlement at a glance: how big, how old, what land it has laid out,
     * and — the question that is otherwise pure guesswork — why it is or is not
     * growing right now.
     */
    private static int colony(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        ZoneManager zones = ZoneManager.get(level);
        ColonyData data = ColonyData.get(level);
        BlockPos center = zones.townCenter(level);
        int population = CitizenIndex.population();

        source.sendSuccess(() -> Component.literal(String.format(
                "Colony — %d citizen(s), day %d, centre %d,%d,%d (radius %d)",
                population, data.ageInDays(level.getGameTime()),
                center.getX(), center.getY(), center.getZ(), zones.radius())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "  %d born, %d lost   %d chunk(s) held open",
                data.births(), data.deaths(),
                ai.minecivilization.colony.ColonyChunkLoader.loadedChunkCount())), false);

        int food = ColonyLife.foodInStore(level, center);
        int beds = ColonyCensus.beds();
        source.sendSuccess(() -> Component.literal(String.format(
                "  food in store %d/%d   beds %d/%d",
                food, Population.foodNeededFor(population),
                beds, Population.bedsNeededFor(population))), false);

        String blocker = ColonyLife.growthBlocker(level);
        source.sendSuccess(() -> Component.literal(blocker == null
                ? "  growth: ready — the next citizen is due"
                : "  growth: held back — " + blocker), false);

        // What the colony is physically building. Without this line, "the
        // citizens never build anything" is a feeling rather than a fact.
        var snapshot = ai.minecivilization.telemetry.ColonySnapshot.take(level, 0);
        var active = snapshot.activeProjects();
        source.sendSuccess(() -> Component.literal(String.format(
                "  %d%% of citizens productive   %d building, %d finished   "
                        + "%d road(s), %d sign(s)",
                snapshot.productivity().busyPercent(), active.size(),
                snapshot.completedProjects(), snapshot.roads().size(), snapshot.signs())),
                false);
        if (!active.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Under construction:"), false);
            for (var project : active) {
                String line = String.format("  %-24s %3d%%  [%s] at %d,%d,%d%s",
                        project.name(), project.percent(), project.status(),
                        project.origin().getX(), project.origin().getY(),
                        project.origin().getZ(),
                        project.waitingFor().isBlank() ? ""
                                : "  waiting for " + project.waitingFor());
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        if (!snapshot.inTrouble().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    snapshot.inTrouble().size() + " citizen(s) need help — /mciv work")
                    .withStyle(net.minecraft.ChatFormatting.RED), false);
        }

        List<Zone> districts = zones.all();
        if (districts.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  no districts laid out yet (they appear as the colony grows)"), false);
            return population;
        }
        source.sendSuccess(() -> Component.literal("Districts:"), false);
        districts.sort(java.util.Comparator.comparing(z -> z.type.ordinal()));
        for (Zone zone : districts) {
            StringBuilder line = new StringBuilder(String.format(
                    "  %-12s %-22s at %d,%d", zone.type.name(), zone.name,
                    zone.centerX(), zone.centerZ()));
            if (!zone.species.isEmpty()) {
                line.append("  growing: ").append(String.join(", ", zone.species));
            }
            String text = line.toString();
            source.sendSuccess(() -> Component.literal(text), false);
        }
        return population;
    }

    // ------------------------------------------------------------------ storage

    /**
     * The warehouse as the citizens see it: which container holds which
     * category, and what the settlement collectively owns. Useful for telling
     * "nobody has registered a chest yet" apart from "the chests are empty".
     */
    private static int storage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPos.containing(source.getPosition());

        List<StorageNode> nodes = StorageManager.get(level).all();
        if (nodes.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No containers registered yet. Place a chest near the camp — "
                    + "citizens register it on sight."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Warehouse — " + nodes.size() + " container(s):"), false);
        nodes.sort(java.util.Comparator.comparingDouble(n -> n.containerPos().distSqr(from)));
        for (StorageNode node : nodes) {
            BlockPos pos = node.containerPos();
            String shelf = node.category == null ? "unassigned" : node.category.label();
            int items = 0;
            var container = SettlementStock.containerAt(level, node);
            if (container != null) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    items += container.getItem(i).getCount();
                }
            }
            String line = String.format("  %s  [%s]  %d item(s)  at %d,%d,%d",
                    node.storageId, shelf, items, pos.getX(), pos.getY(), pos.getZ());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        var totals = SettlementStock.totals(level, from);
        if (totals.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  (all empty)"), false);
            return nodes.size();
        }

        // Biggest stocks first: that is what a planner actually cares about.
        var top = totals.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .toList();
        source.sendSuccess(() -> Component.literal("Settlement stock (top "
                + top.size() + " of " + totals.size() + "):"), false);
        for (var entry : top) {
            String line = String.format("  %-34s %5d   [%s]", entry.getKey(), entry.getValue(),
                    ItemCategory.of(entry.getKey()).label());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return nodes.size();
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
        var from = source.getPosition();
        citizens.sort(java.util.Comparator.comparingDouble(
                c -> c.distanceToSqr(from.x, from.y, from.z)));
        for (CitizenEntity citizen : citizens) {
            var id = citizen.getIdentity();
            double dx = citizen.getX() - from.x;
            double dz = citizen.getZ() - from.z;
            int distance = (int) Math.sqrt(dx * dx + dz * dz);
            source.sendSuccess(() -> Component.literal(String.format(
                    "%-12s %-11s %4dm %-2s  at %d,%d,%d  hunger %.0f  %s: %s",
                    id.name, id.profession, distance, CitizenMarkers.bearing(dx, dz),
                    (int) citizen.getX(), (int) citizen.getY(), (int) citizen.getZ(),
                    citizen.getHunger(),
                    citizen.getStatusName(),
                    citizen.getCitizenBrain().currentGoal() == null
                            ? "idle" : citizen.getCitizenBrain().currentGoal().type.name())), false);
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
        // The structured readout says what the ad-hoc lines above cannot:
        // where it is going, why, what is wrong and what it will try next.
        for (String line : ai.minecivilization.telemetry.CitizenReport
                .of(source.getLevel(), citizen).lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
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

    // ------------------------------------------------------------------ camp

    /** The settlement camp: its anchor (the player's bed) and its houses. */
    private static int camp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        ConstructionManager manager = ConstructionManager.get(level);
        BlockPos anchor = manager.resolveAnchor(level);
        BlockPos bed = manager.campAnchor();
        String origin = bed == null ? "world spawn — no player bed yet" : "player's bed";
        source.sendSuccess(() -> Component.literal(String.format(
                "camp anchor=%d,%d,%d (%s)",
                anchor.getX(), anchor.getY(), anchor.getZ(), origin)), false);

        int houses = 0;
        for (ConstructionProject p : manager.all()) {
            if (!HouseCatalog.isHouse(p.blueprintId)
                    && !ai.minecivilization.construction.StarterHouse.ID.equals(p.blueprintId)) continue;
            houses++;
            Blueprint bp = ConstructionManager.blueprint(p.blueprintId);
            String progress = bp == null ? "" : String.format(" %.0f%%", p.progress(bp) * 100);
            String line = String.format("  %s [%s] origin=%d,%d,%d%s",
                    p.name, p.status, p.originX, p.originY, p.originZ, progress);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        int count = houses;
        source.sendSuccess(() -> Component.literal(count + " house project(s)."), false);
        return houses;
    }

    // ------------------------------------------------------------------ supervision

    /**
     * The colony's newsfeed.
     *
     * <p>The single most useful command for understanding a colony you left
     * running: discoveries, builds started and finished, people getting lost
     * and digging themselves out, roads appearing, signs going up.</p>
     */
    private static int events(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        var log = ai.minecivilization.telemetry.ColonyEventLog.of(level);
        var entries = log.recent(count);

        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Nothing has happened yet — the colony has only just started."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Colony events (" + entries.size() + " of " + log.sequence() + "):"), false);
        long now = level.getGameTime();
        for (var entry : entries) {
            long agoSeconds = Math.max(0, (now - entry.gameTime()) / 20);
            String when = agoSeconds < 60 ? agoSeconds + "s"
                    : agoSeconds < 3600 ? (agoSeconds / 60) + "m" : (agoSeconds / 3600) + "h";
            String line = String.format("  %-4s [%-14s] %s", when,
                    entry.kind().label(), entry.line());
            source.sendSuccess(() -> Component.literal(line)
                    .withStyle(colourFor(entry.kind())), false);
        }
        return entries.size();
    }

    private static net.minecraft.ChatFormatting colourFor(
            ai.minecivilization.telemetry.ColonyEventLog.Kind kind) {
        return switch (kind) {
            case DANGER, RESCUE -> net.minecraft.ChatFormatting.RED;
            case CONSTRUCTION -> net.minecraft.ChatFormatting.GOLD;
            case DISCOVERY -> net.minecraft.ChatFormatting.AQUA;
            case INFRASTRUCTURE -> net.minecraft.ChatFormatting.YELLOW;
            case POPULATION -> net.minecraft.ChatFormatting.GREEN;
            default -> net.minecraft.ChatFormatting.GRAY;
        };
    }

    /** The road network: what the colony walks, and how built-up each way is. */
    private static int roads(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        var memory = ai.minecivilization.roads.PathMemory.get(level);
        var routes = memory.busiest(24);

        if (routes.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No routes remembered yet — roads appear where citizens repeatedly walk."),
                    false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "Roads — %d route(s) known, %d built:", memory.count(), memory.builtCount())),
                false);
        long now = level.getGameTime();
        for (var route : routes) {
            String line = String.format("  %-28s %-16s %4d trips  %4dm  %s%s",
                    route.displayName(), route.grade.label(), route.uses, route.lengthBlocks,
                    route.pendingUpgrade() == null ? "up to date"
                            : "next: " + route.pendingUpgrade().label(),
                    route.isDangerous() ? "  ! DANGEROUS" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return routes.size();
    }

    /** Every sign the colony has put up or read. */
    private static int signs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPos.containing(source.getPosition());
        var registry = ai.minecivilization.colony.SignRegistry.get(level);
        var all = registry.all();

        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "The colony has not signposted anything yet."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Signs — " + all.size() + ":"), false);
        all.sort(java.util.Comparator.comparingDouble(m -> m.pos().distSqr(from)));
        for (var marker : all.subList(0, Math.min(30, all.size()))) {
            String line = String.format("  %-22s %-30s %4dm at %d,%d,%d%s",
                    marker.kind().label(), marker.text(),
                    (int) Math.sqrt(marker.pos().distSqr(from)),
                    marker.pos().getX(), marker.pos().getY(), marker.pos().getZ(),
                    marker.placedByColony() ? "" : "  (read from the world)");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    /** What the colony's work board is offering, and who is holding what. */
    private static int work(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        var census = ai.minecivilization.work.ProductivityMonitor.census();

        source.sendSuccess(() -> Component.literal(String.format(
                "Work — %d citizen(s), %d%% productive, %d claim(s) held over %d source(s)",
                census.population(), census.busyPercent(),
                ai.minecivilization.work.GlobalTaskPool.claimCount(),
                ai.minecivilization.work.GlobalTaskPool.sourceCount())), false);

        for (var state : ai.minecivilization.work.ProductivityMonitor.State.values()) {
            int count = census.count(state);
            if (count == 0) continue;
            String line = String.format("  %-10s %d", state.label(), count);
            source.sendSuccess(() -> Component.literal(line), false);
        }

        // The number that answers "why do they look idle?" with evidence.
        var breakdown = ai.minecivilization.telemetry.ActivityLedger.breakdown();
        if (!breakdown.isEmpty()) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "Where the time goes (%ds observed, %d%% wasted):",
                    ai.minecivilization.telemetry.ActivityLedger.observedSeconds(),
                    ai.minecivilization.telemetry.ActivityLedger.wastedPercent())), false);
            for (var slice : breakdown) {
                if (slice.percent() < 1) continue;
                String line = String.format("  %3d%%  %s", slice.percent(), slice.label());
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }

        source.sendSuccess(() -> Component.literal("Sources, most urgent first:"), false);
        for (String name : ai.minecivilization.work.GlobalTaskPool.sourceNames()) {
            source.sendSuccess(() -> Component.literal("  " + name), false);
        }

        long now = level.getGameTime();
        var trouble = ai.minecivilization.work.ProductivityMonitor.needingAttention(now);
        if (!trouble.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Needing attention:")
                    .withStyle(net.minecraft.ChatFormatting.RED), false);
            for (CitizenEntity citizen : trouble) {
                var report = ai.minecivilization.telemetry.CitizenReport.of(level, citizen);
                source.sendSuccess(() -> Component.literal("  " + report.summaryLine())
                        .withStyle(net.minecraft.ChatFormatting.RED), false);
            }
        }
        return census.population();
    }

    /**
     * The colony from above.
     *
     * <p>A character grid rather than a rendered image, which is enough to see
     * the shape of the place — where the districts are, which way the roads
     * run, and whether anybody is stranded a long way from everyone else.</p>
     */
    private static int map(CommandContext<CommandSourceStack> ctx, int width) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        var snapshot = ai.minecivilization.telemetry.ColonySnapshot.take(level, 0);
        BlockPos viewer = BlockPos.containing(source.getPosition());

        int height = Math.max(9, width / 2);
        var map = ai.minecivilization.telemetry.MiniMap.of(snapshot, viewer, width, height);

        source.sendSuccess(() -> Component.literal(String.format(
                "%s   1 char = %d blocks   N is up",
                snapshot.headline(), map.scale())), false);
        for (String line : map.render()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        StringBuilder legend = new StringBuilder("  ");
        map.legend().forEach((glyph, label) ->
                legend.append(glyph).append('=').append(label).append("  "));
        source.sendSuccess(() -> Component.literal(legend.toString()), false);
        return snapshot.population();
    }

    // ------------------------------------------------------------------ one citizen

    /** Everything known about one citizen, in the order you want to read it. */
    private static int report(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;

        var report = ai.minecivilization.telemetry.CitizenReport.of(source.getLevel(), citizen);
        for (String line : report.lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        if (!report.recentEvents().isEmpty()) {
            source.sendSuccess(() -> Component.literal("  Recent:      "
                    + String.join(" | ", report.recentEvents())), false);
        }
        return 1;
    }

    /**
     * Point a player at a citizen and keep pointing.
     *
     * <p>Coordinates in chat are something you have to hold in your head while
     * you walk; this is a live arrow on the action bar instead.</p>
     */
    private static int track(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;

        net.minecraft.server.level.ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            source.sendFailure(Component.literal("Tracking needs a player to track from."));
            return 0;
        }
        ai.minecivilization.telemetry.CitizenTracker.track(player, citizen,
                source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal(
                "Now tracking " + citizen.getIdentity().name
                        + " — watch the action bar. /mciv track off to stop."), false);
        return 1;
    }

    private static int trackOff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ai.minecivilization.telemetry.CitizenTracker.stop(source.getPlayerOrException());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            source.sendFailure(Component.literal("Nothing to stop tracking."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Tracking off."), false);
        return 1;
    }

    /**
     * Order a citizen to drop everything and get itself home.
     *
     * <p>The colony does this by itself; this is for when a player can see
     * somebody stuck and would rather not wait for the monitor to notice.</p>
     */
    private static int rescue(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CitizenEntity citizen = resolve(source, ctx);
        if (citizen == null) return 0;

        citizen.setWorkAllowed(true);
        boolean engaged = citizen.getCitizenBrain().forceRescue(source.getLevel(), citizen);
        source.sendSuccess(() -> Component.literal(engaged
                ? citizen.getIdentity().name + " is making its way home — "
                        + "digging out if it has to."
                : citizen.getIdentity().name + " is already home; back to work."), true);
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
