package ai.minecivilization.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * The colony seen from above.
 *
 * <p>A list of coordinates does not tell you that the mine is on the wrong
 * side of the ravine, that three houses are in a row and the fourth is
 * nowhere near them, or that the road to the forest goes the long way round.
 * A picture does, immediately.</p>
 *
 * <p>Deliberately a grid of characters rather than a rendered image. That is
 * enough to answer the shape questions today through a command, it costs
 * nothing, and — because the projection and the layering are separate from the
 * drawing — a graphical map later is a different {@code render} on the same
 * {@link Cell} grid rather than a rewrite.</p>
 *
 * <p>Pure: markers in, a grid out, no world access at all. The projection maths
 * is therefore unit tested rather than eyeballed.</p>
 */
public final class MiniMap {

    /** What a cell of the map is showing, highest priority last. */
    public enum Cell {
        EMPTY(' ', "open country"),
        EXPLORED('·', "explored"),
        ROAD('=', "road"),
        DISTRICT('+', "district"),
        FARM('"', "farm"),
        FOREST('t', "forest"),
        MINE('v', "mine"),
        STORAGE('S', "warehouse"),
        BUILDING('#', "building"),
        PROJECT('%', "under construction"),
        CITIZEN('o', "citizen"),
        TROUBLE('!', "citizen in trouble"),
        TOWN_HALL('@', "town hall"),
        VIEWER('x', "you");

        public final char glyph;
        public final String label;

        Cell(char glyph, String label) {
            this.glyph = glyph;
            this.label = label;
        }

        /** The more important of two things wanting the same cell. */
        public Cell merge(Cell other) {
            return other == null || ordinal() >= other.ordinal() ? this : other;
        }
    }

    /** Something to draw, in world coordinates. */
    public record Marker(int x, int z, Cell cell) {
    }

    private final int width;
    private final int height;
    private final int centreX;
    private final int centreZ;
    /** World blocks per map cell. */
    private final int scale;
    private final Cell[][] grid;

    public MiniMap(int centreX, int centreZ, int width, int height, int scale) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.scale = Math.max(1, scale);
        this.grid = new Cell[this.height][this.width];
        for (Cell[] row : grid) Arrays.fill(row, Cell.EMPTY);
    }

    /**
     * A map sized to hold everything given, at the coarsest scale that fits.
     *
     * <p>Choosing the scale from the content is what stops a map being either
     * a single dot or a sea of blanks as the colony grows.</p>
     */
    public static MiniMap fitting(int centreX, int centreZ, int width, int height,
                                  List<Marker> markers) {
        int reach = 16;
        for (Marker marker : markers) {
            reach = Math.max(reach, Math.abs(marker.x() - centreX));
            reach = Math.max(reach, Math.abs(marker.z() - centreZ));
        }
        // Half the grid must cover the furthest marker, rounded up.
        int cellsFromCentre = Math.max(1, Math.min(width, height) / 2);
        int scale = Math.max(1, (reach + cellsFromCentre - 1) / cellsFromCentre);
        MiniMap map = new MiniMap(centreX, centreZ, width, height, scale);
        markers.forEach(map::plot);
        return map;
    }

    // ------------------------------------------------------------------ drawing

    /** Put a marker on the map, if it is on the map at all. */
    public boolean plot(Marker marker) {
        return plot(marker.x(), marker.z(), marker.cell());
    }

    public boolean plot(int worldX, int worldZ, Cell cell) {
        int col = column(worldX);
        int row = row(worldZ);
        if (col < 0 || col >= width || row < 0 || row >= height) return false;
        grid[row][col] = cell.merge(grid[row][col]);
        return true;
    }

    /** Draw a line of cells between two points — how roads get onto the map. */
    public void plotLine(int fromX, int fromZ, int toX, int toZ, Cell cell) {
        int steps = Math.max(Math.abs(toX - fromX), Math.abs(toZ - fromZ)) / Math.max(1, scale / 2 + 1);
        steps = Math.max(1, Math.min(steps, 512));
        for (int i = 0; i <= steps; i++) {
            plot(fromX + (toX - fromX) * i / steps, fromZ + (toZ - fromZ) * i / steps, cell);
        }
    }

    /** Map column for a world X, which may be off the map. */
    public int column(int worldX) {
        return Math.floorDiv(worldX - centreX, scale) + width / 2;
    }

    /** Map row for a world Z. Rows run north to south, like a printed map. */
    public int row(int worldZ) {
        return Math.floorDiv(worldZ - centreZ, scale) + height / 2;
    }

    // ------------------------------------------------------------------ output

    /** The map as lines of text, north at the top. */
    public List<String> render() {
        List<String> lines = new ArrayList<>(height);
        for (Cell[] row : grid) {
            StringBuilder line = new StringBuilder(width);
            for (Cell cell : row) line.append(cell.glyph);
            lines.add(line.toString());
        }
        return lines;
    }

    /** Only the symbols actually used, so the key is never longer than the map. */
    public Map<Character, String> legend() {
        Map<Character, String> used = new LinkedHashMap<>();
        for (Cell[] row : grid) {
            for (Cell cell : row) {
                if (cell != Cell.EMPTY) used.putIfAbsent(cell.glyph, cell.label);
            }
        }
        return used;
    }

    public int scale() {
        return scale;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** What a cell holds, for tests and for a graphical renderer. */
    public Cell at(int column, int row) {
        if (column < 0 || column >= width || row < 0 || row >= height) return Cell.EMPTY;
        return grid[row][column];
    }

    // ------------------------------------------------------------------ from a snapshot

    /**
     * Everything in a colony, ready to plot.
     *
     * <p>Order matters only in that the {@link Cell} priority resolves
     * collisions — a citizen standing in a district shows as a citizen, and a
     * citizen in trouble shows as trouble.</p>
     */
    public static List<Marker> markersFor(ColonySnapshot snapshot, BlockPos viewer) {
        List<Marker> markers = new ArrayList<>();

        for (var district : snapshot.districts()) {
            Cell cell = switch (district.type()) {
                case "FARM" -> Cell.FARM;
                case "FOREST" -> Cell.FOREST;
                case "MINE" -> Cell.MINE;
                case "STORAGE" -> Cell.STORAGE;
                case "CIVIC" -> Cell.TOWN_HALL;
                default -> Cell.DISTRICT;
            };
            markers.add(new Marker(district.centerX(), district.centerZ(), cell));
        }
        for (var project : snapshot.projects()) {
            markers.add(new Marker(project.origin().getX(), project.origin().getZ(),
                    "COMPLETED".equals(project.status()) ? Cell.BUILDING : Cell.PROJECT));
        }
        for (var citizen : snapshot.citizens()) {
            boolean trouble = citizen.state() == ai.minecivilization.work.ProductivityMonitor.State.LOST
                    || citizen.state() == ai.minecivilization.work.ProductivityMonitor.State.STUCK;
            markers.add(new Marker(citizen.position().getX(), citizen.position().getZ(),
                    trouble ? Cell.TROUBLE : Cell.CITIZEN));
        }
        markers.add(new Marker(snapshot.centre().getX(), snapshot.centre().getZ(), Cell.TOWN_HALL));
        if (viewer != null) {
            markers.add(new Marker(viewer.getX(), viewer.getZ(), Cell.VIEWER));
        }
        return markers;
    }

    /** A finished map of a colony, roads included. */
    public static MiniMap of(ColonySnapshot snapshot, BlockPos viewer, int width, int height) {
        List<Marker> markers = markersFor(snapshot, viewer);
        MiniMap map = fitting(snapshot.centre().getX(), snapshot.centre().getZ(),
                width, height, markers);
        // Roads are drawn first so buildings and people sit on top of them.
        for (var road : snapshot.roads()) {
            map.plotLine(road.from().getX(), road.from().getZ(),
                    road.to().getX(), road.to().getZ(), Cell.ROAD);
        }
        markers.forEach(map::plot);
        return map;
    }
}
