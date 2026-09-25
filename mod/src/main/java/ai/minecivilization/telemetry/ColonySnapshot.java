package ai.minecivilization.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.colony.ColonyCensus;
import ai.minecivilization.colony.ColonyData;
import ai.minecivilization.colony.ColonyLife;
import ai.minecivilization.colony.SignRegistry;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.roads.PathMemory;
import ai.minecivilization.roads.Route;
import ai.minecivilization.storage.SettlementStock;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.work.ProductivityMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The whole colony, as one readable object.
 *
 * <p>Answering "how is the settlement doing?" used to mean running five
 * commands and holding the answers in your head. This gathers them: who is
 * working and who is not, what is being built, what the warehouse holds, how
 * many roads and districts exist, and what has just happened.</p>
 *
 * <p>A snapshot is a value, taken at a moment and then immutable, which is what
 * makes it safe to hand to anything — a command, a log line, an overlay, or a
 * future screen — without that consumer having to know how to walk the
 * colony's data structures safely.</p>
 */
public record ColonySnapshot(
        String name,
        long gameTime,
        long ageInDays,
        BlockPos centre,
        int radius,
        int population,
        int births,
        int deaths,
        ProductivityMonitor.Census productivity,
        int food,
        int beds,
        String growthBlocker,
        List<ProjectSummary> projects,
        List<DistrictSummary> districts,
        List<RoadSummary> roads,
        Map<String, Integer> topStock,
        int containers,
        int signs,
        int landmarks,
        List<CitizenReport> citizens,
        List<ColonyEventLog.Entry> events) {

    /** One thing being built. */
    public record ProjectSummary(String id, String name, String status, int percent,
                                 BlockPos origin, String waitingFor) {
    }

    /** One allotted piece of land. */
    public record DistrictSummary(String id, String type, String name, int centerX, int centerZ) {
    }

    /** One remembered journey. */
    public record RoadSummary(String id, String name, String grade, int uses, int length,
                              BlockPos from, BlockPos to, boolean dangerous) {
    }

    /** How many stock lines and events a snapshot carries — a briefing, not a dump. */
    public static final int STOCK_LINES = 12;
    public static final int EVENT_LINES = 20;

    /**
     * Take a snapshot.
     *
     * <p>Reasonably cheap, but not free — it walks every citizen and every
     * container — so it is taken on demand rather than every tick.</p>
     */
    public static ColonySnapshot take(ServerLevel level) {
        return take(level, EVENT_LINES);
    }

    public static ColonySnapshot take(ServerLevel level, int eventLimit) {
        ZoneManager zones = ZoneManager.get(level);
        ColonyData data = ColonyData.get(level);
        BlockPos centre = zones.townCenter(level);
        int population = CitizenIndex.population();

        List<ProjectSummary> projects = new ArrayList<>();
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            var blueprint = ConstructionManager.blueprint(project.blueprintId);
            int percent = blueprint == null ? 0 : (int) Math.round(project.progress(blueprint) * 100);
            String waiting = "";
            if (project.status == ConstructionProject.Status.WAITING_FOR_RESOURCES
                    && !CitizenIndex.all().isEmpty()) {
                var shortfall = ai.minecivilization.work.ConstructionSupply.nextShortfall(
                        level, project, CitizenIndex.all().get(0));
                if (shortfall != null) {
                    waiting = shortfall.needed() + "x "
                            + ai.minecivilization.work.ColonyWork.shortName(shortfall.itemId());
                }
            }
            projects.add(new ProjectSummary(project.id, project.name, project.status.name(),
                    percent, new BlockPos(project.originX, project.originY, project.originZ),
                    waiting));
        }

        List<DistrictSummary> districts = new ArrayList<>();
        for (Zone zone : zones.all()) {
            districts.add(new DistrictSummary(zone.id, zone.type.name(), zone.name,
                    zone.centerX(), zone.centerZ()));
        }

        List<RoadSummary> roads = new ArrayList<>();
        for (Route route : PathMemory.get(level).busiest(16)) {
            roads.add(new RoadSummary(route.id, route.displayName(), route.grade.label(),
                    route.uses, route.lengthBlocks, route.from, route.to, route.isDangerous()));
        }

        Map<String, Integer> stock = new LinkedHashMap<>();
        SettlementStock.totals(level, centre).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(STOCK_LINES)
                .forEach(entry -> stock.put(entry.getKey(), entry.getValue()));

        List<CitizenReport> citizens = new ArrayList<>();
        for (CitizenEntity citizen : CitizenIndex.all()) {
            citizens.add(CitizenReport.of(level, citizen));
        }

        return new ColonySnapshot(
                data.name(),
                level.getGameTime(),
                data.ageInDays(level.getGameTime()),
                centre,
                zones.radius(),
                population,
                data.births(),
                data.deaths(),
                ProductivityMonitor.census(),
                ColonyLife.foodInStore(level, centre),
                ColonyCensus.beds(),
                ColonyLife.growthBlocker(level),
                projects,
                districts,
                roads,
                stock,
                StorageManager.get(level).all().size(),
                SignRegistry.get(level).size(),
                ai.minecivilization.colony.LandmarkRegistry.get(level).all().size(),
                citizens,
                ColonyEventLog.of(level).recent(eventLimit));
    }

    // ------------------------------------------------------------------ views

    /** Projects still being worked on. */
    public List<ProjectSummary> activeProjects() {
        List<ProjectSummary> out = new ArrayList<>();
        for (ProjectSummary project : projects) {
            if (!"COMPLETED".equals(project.status()) && !"FAILED".equals(project.status())) {
                out.add(project);
            }
        }
        return out;
    }

    public int completedProjects() {
        int n = 0;
        for (ProjectSummary project : projects) {
            if ("COMPLETED".equals(project.status())) n++;
        }
        return n;
    }

    /** Citizens a player should probably go and look at. */
    public List<CitizenReport> inTrouble() {
        List<CitizenReport> out = new ArrayList<>();
        for (CitizenReport report : citizens) {
            if (report.state() == ProductivityMonitor.State.LOST
                    || report.state() == ProductivityMonitor.State.STUCK) {
                out.add(report);
            }
        }
        return out;
    }

    /** The one-line verdict: is this colony alive and building? */
    public String headline() {
        return String.format("%s — day %d, %d citizen(s), %d%% busy, %d building, %d road(s)",
                name, ageInDays, population, productivity.busyPercent(),
                activeProjects().size(), roads.size());
    }
}
