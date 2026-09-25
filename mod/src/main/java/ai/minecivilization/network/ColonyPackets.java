package ai.minecivilization.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.telemetry.CitizenReport;
import ai.minecivilization.telemetry.ColonyEventLog;
import ai.minecivilization.telemetry.ColonySnapshot;
import ai.minecivilization.work.ProductivityMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Getting the colony onto the player's screen.
 *
 * <p>Everything the supervision window shows lives on the server: the citizens
 * are server entities, the districts and roads are saved data, the event feed
 * is a server-side ring. The client knows none of it. These are the two
 * messages that bridge the gap — a snapshot going out, and a request coming
 * back.</p>
 *
 * <p>The wire format is written by hand rather than reflected, because a
 * snapshot is sent several times a second to every player with the window open
 * and its size is the only thing standing between a useful live view and a
 * laggy one. Strings are bounded, lists are capped, and nothing is sent that
 * the window does not draw.</p>
 */
public final class ColonyPackets {

    /** Hard caps, so one enormous colony cannot produce an enormous packet. */
    private static final int MAX_CITIZENS = 256;
    private static final int MAX_ROWS = 64;
    private static final int MAX_STRING = 256;

    private ColonyPackets() {
    }

    // ------------------------------------------------------------------ messages

    /** Server → client: everything the window draws, as of this moment. */
    public record Snapshot(ColonySnapshot colony) {

        public static void encode(Snapshot message, FriendlyByteBuf buf) {
            writeSnapshot(buf, message.colony());
        }

        public static Snapshot decode(FriendlyByteBuf buf) {
            return new Snapshot(readSnapshot(buf));
        }
    }

    /** What a player can ask for from the window. */
    public enum Action {
        /** Send me a fresh snapshot. */
        REFRESH,
        /** Follow this citizen on my action bar. */
        TRACK,
        /** Stop following anyone. */
        UNTRACK,
        /** Send this citizen home, digging out if it must. */
        RESCUE,
        /** Give this citizen a new decision now. */
        THINK,
        /** Outline every citizen through terrain, or stop. */
        HIGHLIGHT
    }

    /**
     * Client → server: a request, optionally about one citizen.
     *
     * <p>The citizen is named rather than referenced, because the client's copy
     * of an entity may have been unloaded by the time the request lands — and
     * because names are now unique, which is what makes that safe.</p>
     */
    public record Request(Action action, String citizen) {

        public static Request refresh() {
            return new Request(Action.REFRESH, "");
        }

        public static Request about(Action action, String citizen) {
            return new Request(action, citizen == null ? "" : citizen);
        }

        public static void encode(Request message, FriendlyByteBuf buf) {
            buf.writeEnum(message.action());
            buf.writeUtf(message.citizen(), MAX_STRING);
        }

        public static Request decode(FriendlyByteBuf buf) {
            return new Request(buf.readEnum(Action.class), buf.readUtf(MAX_STRING));
        }
    }

    // ------------------------------------------------------------------ snapshot codec

    private static void writeSnapshot(FriendlyByteBuf buf, ColonySnapshot colony) {
        buf.writeUtf(colony.name(), MAX_STRING);
        buf.writeVarLong(colony.gameTime());
        buf.writeVarLong(colony.ageInDays());
        buf.writeBlockPos(colony.centre());
        buf.writeVarInt(colony.radius());
        buf.writeVarInt(colony.population());
        buf.writeVarInt(colony.births());
        buf.writeVarInt(colony.deaths());

        // Productivity census.
        var census = colony.productivity();
        buf.writeVarInt(census.population());
        buf.writeVarInt(census.byState().size());
        for (var entry : census.byState().entrySet()) {
            buf.writeEnum(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(census.stuckCount());
        buf.writeDouble(census.busyFraction());

        buf.writeVarInt(colony.food());
        buf.writeVarInt(colony.beds());
        buf.writeUtf(colony.growthBlocker() == null ? "" : colony.growthBlocker(), MAX_STRING);

        writeList(buf, colony.projects(), (b, project) -> {
            b.writeUtf(project.id(), 64);
            b.writeUtf(project.name(), MAX_STRING);
            b.writeUtf(project.status(), 32);
            b.writeVarInt(project.percent());
            b.writeBlockPos(project.origin());
            b.writeUtf(project.waitingFor(), MAX_STRING);
        });
        writeList(buf, colony.districts(), (b, district) -> {
            b.writeUtf(district.id(), 64);
            b.writeUtf(district.type(), 32);
            b.writeUtf(district.name(), MAX_STRING);
            b.writeVarInt(district.centerX());
            b.writeVarInt(district.centerZ());
        });
        writeList(buf, colony.roads(), (b, road) -> {
            b.writeUtf(road.id(), 64);
            b.writeUtf(road.name(), MAX_STRING);
            b.writeUtf(road.grade(), 32);
            b.writeVarInt(road.uses());
            b.writeVarInt(road.length());
            b.writeBlockPos(road.from());
            b.writeBlockPos(road.to());
            b.writeBoolean(road.dangerous());
        });

        buf.writeVarInt(Math.min(MAX_ROWS, colony.topStock().size()));
        int written = 0;
        for (var entry : colony.topStock().entrySet()) {
            if (written++ >= MAX_ROWS) break;
            buf.writeUtf(entry.getKey(), MAX_STRING);
            buf.writeVarInt(entry.getValue());
        }

        buf.writeVarInt(colony.containers());
        buf.writeVarInt(colony.signs());
        buf.writeVarInt(colony.landmarks());

        int citizens = Math.min(MAX_CITIZENS, colony.citizens().size());
        buf.writeVarInt(citizens);
        for (int i = 0; i < citizens; i++) {
            writeCitizen(buf, colony.citizens().get(i));
        }
        writeList(buf, colony.events(), (b, event) -> {
            b.writeVarLong(event.gameTime());
            b.writeEnum(event.kind());
            b.writeUtf(event.actor() == null ? "" : event.actor(), MAX_STRING);
            b.writeUtf(event.message(), MAX_STRING);
        });
    }

    private static ColonySnapshot readSnapshot(FriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_STRING);
        long gameTime = buf.readVarLong();
        long age = buf.readVarLong();
        BlockPos centre = buf.readBlockPos();
        int radius = buf.readVarInt();
        int population = buf.readVarInt();
        int births = buf.readVarInt();
        int deaths = buf.readVarInt();

        int censusPopulation = buf.readVarInt();
        int states = buf.readVarInt();
        Map<ProductivityMonitor.State, Integer> byState =
                new java.util.EnumMap<>(ProductivityMonitor.State.class);
        for (int i = 0; i < states; i++) {
            byState.put(buf.readEnum(ProductivityMonitor.State.class), buf.readVarInt());
        }
        int stuck = buf.readVarInt();
        double busy = buf.readDouble();
        var census = new ProductivityMonitor.Census(censusPopulation, byState, stuck,
                busy, gameTime);

        int food = buf.readVarInt();
        int beds = buf.readVarInt();
        String blocker = buf.readUtf(MAX_STRING);

        List<ColonySnapshot.ProjectSummary> projects = readList(buf, b ->
                new ColonySnapshot.ProjectSummary(b.readUtf(64), b.readUtf(MAX_STRING),
                        b.readUtf(32), b.readVarInt(), b.readBlockPos(), b.readUtf(MAX_STRING)));
        List<ColonySnapshot.DistrictSummary> districts = readList(buf, b ->
                new ColonySnapshot.DistrictSummary(b.readUtf(64), b.readUtf(32),
                        b.readUtf(MAX_STRING), b.readVarInt(), b.readVarInt()));
        List<ColonySnapshot.RoadSummary> roads = readList(buf, b ->
                new ColonySnapshot.RoadSummary(b.readUtf(64), b.readUtf(MAX_STRING),
                        b.readUtf(32), b.readVarInt(), b.readVarInt(),
                        b.readBlockPos(), b.readBlockPos(), b.readBoolean()));

        int stockSize = buf.readVarInt();
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (int i = 0; i < stockSize; i++) {
            stock.put(buf.readUtf(MAX_STRING), buf.readVarInt());
        }

        int containers = buf.readVarInt();
        int signs = buf.readVarInt();
        int landmarks = buf.readVarInt();

        int citizenCount = buf.readVarInt();
        List<CitizenReport> citizens = new ArrayList<>(citizenCount);
        for (int i = 0; i < citizenCount; i++) {
            citizens.add(readCitizen(buf));
        }
        List<ColonyEventLog.Entry> events = readList(buf, b ->
                new ColonyEventLog.Entry(b.readVarLong(),
                        b.readEnum(ColonyEventLog.Kind.class),
                        b.readUtf(MAX_STRING), b.readUtf(MAX_STRING)));

        return new ColonySnapshot(name, gameTime, age, centre, radius, population,
                births, deaths, census, food, beds, blocker.isEmpty() ? null : blocker,
                projects, districts, roads, stock, containers, signs, landmarks,
                citizens, events);
    }

    // ------------------------------------------------------------------ one citizen

    private static void writeCitizen(FriendlyByteBuf buf, CitizenReport report) {
        buf.writeUtf(report.name(), MAX_STRING);
        buf.writeUtf(report.profession(), 64);
        buf.writeUtf(report.id(), 64);
        buf.writeEnum(report.state());
        buf.writeUtf(report.goal(), MAX_STRING);
        buf.writeUtf(report.task(), MAX_STRING);
        buf.writeUtf(report.subTask(), MAX_STRING);
        buf.writeUtf(report.priority(), 64);
        buf.writeBlockPos(report.position());
        // A destination is optional; a flag is cheaper than a sentinel position.
        buf.writeBoolean(report.destination() != null);
        if (report.destination() != null) buf.writeBlockPos(report.destination());
        buf.writeBlockPos(report.colony());
        buf.writeVarInt(report.distanceHome());
        buf.writeUtf(report.bearingHome(), 8);
        buf.writeVarInt(report.depthBelowSurface());
        buf.writeFloat(report.health());
        buf.writeFloat(report.hunger());
        buf.writeFloat(report.energy());
        buf.writeUtf(report.reason(), MAX_STRING);
        buf.writeUtf(report.problem(), MAX_STRING);
        buf.writeUtf(report.fallback(), MAX_STRING);
        buf.writeUtf(report.lastSuccess(), MAX_STRING);
        buf.writeUtf(report.nextAction(), MAX_STRING);
        buf.writeVarInt(report.completedCount());
        buf.writeVarInt(report.consecutiveFailures());
        buf.writeVarLong(report.stuckTicks());
        buf.writeUtf(report.inventory(), 1024);
        buf.writeUtf(report.claim(), MAX_STRING);
        buf.writeBoolean(report.decisionPending());
        writeList(buf, report.recentEvents(), (b, line) -> b.writeUtf(line, MAX_STRING));
    }

    private static CitizenReport readCitizen(FriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_STRING);
        String profession = buf.readUtf(64);
        String id = buf.readUtf(64);
        var state = buf.readEnum(ProductivityMonitor.State.class);
        String goal = buf.readUtf(MAX_STRING);
        String task = buf.readUtf(MAX_STRING);
        String subTask = buf.readUtf(MAX_STRING);
        String priority = buf.readUtf(64);
        BlockPos position = buf.readBlockPos();
        BlockPos destination = buf.readBoolean() ? buf.readBlockPos() : null;
        BlockPos colony = buf.readBlockPos();
        int distance = buf.readVarInt();
        String bearing = buf.readUtf(8);
        int depth = buf.readVarInt();
        float health = buf.readFloat();
        float hunger = buf.readFloat();
        float energy = buf.readFloat();
        String reason = buf.readUtf(MAX_STRING);
        String problem = buf.readUtf(MAX_STRING);
        String fallback = buf.readUtf(MAX_STRING);
        String lastSuccess = buf.readUtf(MAX_STRING);
        String nextAction = buf.readUtf(MAX_STRING);
        int completed = buf.readVarInt();
        int failures = buf.readVarInt();
        long stuckTicks = buf.readVarLong();
        String inventory = buf.readUtf(1024);
        String claim = buf.readUtf(MAX_STRING);
        boolean pending = buf.readBoolean();
        List<String> events = readList(buf, b -> b.readUtf(MAX_STRING));

        return new CitizenReport(name, profession, id, state, goal, task, subTask,
                priority, position, destination, colony, distance, bearing, depth,
                health, hunger, energy, reason, problem, fallback, lastSuccess,
                nextAction, completed, failures, stuckTicks, inventory, claim,
                pending, events);
    }

    // ------------------------------------------------------------------ list helpers

    private interface Writer<T> {
        void write(FriendlyByteBuf buf, T value);
    }

    private interface Reader<T> {
        T read(FriendlyByteBuf buf);
    }

    private static <T> void writeList(FriendlyByteBuf buf, List<T> values, Writer<T> writer) {
        int count = Math.min(MAX_ROWS, values.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            writer.write(buf, values.get(i));
        }
    }

    private static <T> List<T> readList(FriendlyByteBuf buf, Reader<T> reader) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ROWS) {
            throw new IllegalStateException("colony packet declared " + count + " rows");
        }
        List<T> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(reader.read(buf));
        return out;
    }
}
