package ai.minecivilization.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ai.minecivilization.network.ColonyPackets;
import ai.minecivilization.telemetry.CitizenReport;
import ai.minecivilization.telemetry.ColonyEventLog;
import ai.minecivilization.telemetry.ColonySnapshot;
import ai.minecivilization.telemetry.MiniMap;
import ai.minecivilization.work.ProductivityMonitor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The colony supervision window.
 *
 * <p>Everything this mod does happens on the server, several hundred blocks
 * apart, over hours. Watching it meant reading a log file. This is the window
 * that makes a settlement legible at a glance: who is working and who is
 * stuck, what is being built and what it is waiting for, where everyone is,
 * and what has just happened.</p>
 *
 * <p>Four tabs, because there are four different questions. The window is
 * read-only except for three actions on a selected citizen — follow them,
 * send them home, make them think again — which are the three things you
 * actually want to do about somebody once you have found them.</p>
 */
public final class ColonyScreen extends Screen {

    // ------------------------------------------------------------------ palette

    private static final int BACKGROUND = 0xE8101216;
    private static final int PANEL = 0x30FFFFFF;
    private static final int LINE = 0x40FFFFFF;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF9AA0A6;
    private static final int GOOD = 0xFF7BC47B;
    private static final int WARN = 0xFFE8C46A;
    private static final int BAD = 0xFFE87B7B;
    private static final int ACCENT = 0xFF6AB7E8;
    private static final int SELECTED = 0x506AB7E8;

    private static final int ROW_HEIGHT = 11;
    private static final int MARGIN = 12;
    /**
     * Room above the content.
     *
     * <p>Sized from what the header actually draws — title, tab row, summary —
     * rather than guessed. It was guessed, and the summary line landed on top
     * of the first column heading.</p>
     */
    private static final int HEADER_HEIGHT = MARGIN + 18 + 18 + ROW_HEIGHT + 8;
    /** Width reserved on the right of the header for the busy meter. */
    private static final int METER_WIDTH = 120;

    /** Which question the window is currently answering. */
    private enum Tab {
        OVERVIEW("Overview"),
        CITIZENS("Citizens"),
        MAP("Map"),
        EVENTS("Events");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private Tab tab = Tab.OVERVIEW;
    private int scroll;
    @Nullable
    private String selectedCitizen;

    /** Rows currently drawn in the citizens list, for hit testing. */
    private final List<CitizenReport> visibleRows = new ArrayList<>();
    private int listTop;
    private int listBottom;

    public ColonyScreen() {
        super(Component.literal("Colony"));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int x = MARGIN;
        for (Tab candidate : Tab.values()) {
            int width = Math.max(56, this.font.width(candidate.label) + 16);
            addRenderableWidget(Button.builder(Component.literal(candidate.label), button -> {
                this.tab = candidate;
                this.scroll = 0;
            }).bounds(x, MARGIN + 18, width, 18).build());
            x += width + 4;
        }

        // Actions on the selected citizen, bottom right. They are disabled
        // rather than hidden when nobody is selected, so the window does not
        // change shape as you click around it.
        int buttonY = this.height - MARGIN - 20;
        int buttonX = this.width - MARGIN - 3 * 74;
        addRenderableWidget(Button.builder(Component.literal("Track"), button ->
                act(ColonyPackets.Action.TRACK)).bounds(buttonX, buttonY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Send home"), button ->
                act(ColonyPackets.Action.RESCUE)).bounds(buttonX + 74, buttonY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Rethink"), button ->
                act(ColonyPackets.Action.THINK)).bounds(buttonX + 148, buttonY, 70, 20).build());
    }

    private void act(ColonyPackets.Action action) {
        if (selectedCitizen == null) return;
        ColonyClientState.request(ColonyPackets.Request.about(action, selectedCitizen));
    }

    @Override
    public boolean isPauseScreen() {
        // A colony you can only watch while it is frozen is not much of a
        // colony. The whole point is to see it carrying on.
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ColonyClientState.poll();

        graphics.fill(0, 0, this.width, this.height, BACKGROUND);
        super.render(graphics, mouseX, mouseY, partialTick);

        ColonySnapshot colony = ColonyClientState.snapshot();
        if (colony == null) {
            centred(graphics, "Asking the server about the colony…", DIM);
            return;
        }

        drawHeader(graphics, colony);

        int top = HEADER_HEIGHT + MARGIN;
        int bottom = this.height - MARGIN - 26;
        graphics.fill(MARGIN, top - 4, this.width - MARGIN, top - 3, LINE);

        switch (tab) {
            case OVERVIEW -> drawOverview(graphics, colony, top, bottom);
            case CITIZENS -> drawCitizens(graphics, colony, top, bottom);
            case MAP -> drawMap(graphics, colony, top, bottom);
            case EVENTS -> drawEvents(graphics, colony, top, bottom);
        }
    }

    private void drawHeader(GuiGraphics graphics, ColonySnapshot colony) {
        var census = colony.productivity();
        int busy = census.busyPercent();

        // Right-hand block first, so the title can be clipped against it
        // rather than drawn underneath it.
        int meterX = this.width - MARGIN - METER_WIDTH;
        graphics.fill(meterX, MARGIN, meterX + METER_WIDTH, MARGIN + 8, PANEL);
        graphics.fill(meterX, MARGIN, meterX + Math.max(1, METER_WIDTH * busy / 100),
                MARGIN + 8, busy >= 70 ? GOOD : busy >= 40 ? WARN : BAD);
        graphics.drawString(this.font, busy + "% busy", meterX, MARGIN + 11, DIM, false);

        int trouble = census.count(ProductivityMonitor.State.STUCK)
                + census.count(ProductivityMonitor.State.LOST);
        if (trouble > 0) {
            // Right-aligned inside the window, not off the edge of it.
            String alert = trouble + " need help";
            graphics.drawString(this.font, alert,
                    this.width - MARGIN - this.font.width(alert), MARGIN + 22, BAD, false);
        }

        graphics.drawString(this.font,
                trim(colony.name() + "  —  day " + colony.ageInDays(), meterX - MARGIN - 8),
                MARGIN, MARGIN, TEXT, false);

        // The summary sits on its own line under the tabs, with the stale
        // marker after it instead of on top of it.
        int summaryY = MARGIN + 18 + 18 + 4;
        String summary = String.format(
                "%d citizens · %d%% productive · %d building · %d districts · %d roads · %d signs",
                colony.population(), busy, colony.activeProjects().size(),
                colony.districts().size(), colony.roads().size(), colony.signs());
        long age = ColonyClientState.ageMs();
        if (age > 4000) summary = summary + "   (" + age / 1000 + "s old)";
        graphics.drawString(this.font, trim(summary, this.width - MARGIN * 2), MARGIN,
                summaryY, busy >= 70 ? DIM : WARN, false);
    }

    // ------------------------------------------------------------------ overview

    private void drawOverview(GuiGraphics graphics, ColonySnapshot colony, int top, int bottom) {
        int column = this.width / 2 + 6;
        int leftWidth = column - MARGIN - 12;
        int y = top;

        y = heading(graphics, "Population", MARGIN, y);
        var census = colony.productivity();
        for (ProductivityMonitor.State state : ProductivityMonitor.State.values()) {
            int count = census.count(state);
            if (count == 0) continue;
            int colour = switch (state) {
                case STUCK, LOST -> BAD;
                case IDLE -> WARN;
                case DEAD -> DIM;
                default -> GOOD;
            };
            graphics.drawString(this.font, state.label(), MARGIN + 4, y, DIM, false);
            graphics.drawString(this.font, String.valueOf(count), MARGIN + 90, y, colour, false);
            y += ROW_HEIGHT;
        }
        y += 4;
        y = heading(graphics, "Growth", MARGIN, y);
        y = keyValue(graphics, "food in store", String.valueOf(colony.food()), MARGIN, y);
        y = keyValue(graphics, "beds", String.valueOf(colony.beds()), MARGIN, y);
        y = keyValue(graphics, "born / lost",
                colony.births() + " / " + colony.deaths(), MARGIN, y);
        String blocker = colony.growthBlocker();
        graphics.drawString(this.font, blocker == null ? "ready to grow" : "held back: " + blocker,
                MARGIN + 4, y, blocker == null ? GOOD : WARN, false);
        y += ROW_HEIGHT + 4;

        y = heading(graphics, "Stores", MARGIN, y);
        for (var entry : colony.topStock().entrySet()) {
            if (y > bottom - ROW_HEIGHT) break;
            y = keyValue(graphics, trim(shortName(entry.getKey()), leftWidth - 130),
                    String.valueOf(entry.getValue()), MARGIN, y);
        }

        // Right column: what the colony is physically making of itself.
        int rightY = top;
        rightY = heading(graphics, "Under construction", column, rightY);
        var active = colony.activeProjects();
        if (active.isEmpty()) {
            graphics.drawString(this.font, "nothing — the colony is not building",
                    column + 4, rightY, WARN, false);
            rightY += ROW_HEIGHT;
        }
        int barX = this.width - MARGIN - 60;
        for (var project : active) {
            if (rightY > bottom - ROW_HEIGHT * 2) break;
            // The name must stop before the progress bar, not run under it.
            graphics.drawString(this.font, trim(project.name(), barX - column - 12),
                    column + 4, rightY, TEXT, false);
            String detail = project.percent() + "%"
                    + (project.waitingFor().isBlank() ? "" : "  needs " + project.waitingFor());
            graphics.drawString(this.font, trim(detail, barX - column - 12),
                    column + 4, rightY + ROW_HEIGHT - 1,
                    project.waitingFor().isBlank() ? DIM : WARN, false);
            graphics.fill(barX, rightY + 1, barX + 56, rightY + 6, PANEL);
            graphics.fill(barX, rightY + 1, barX + Math.max(1, 56 * project.percent() / 100),
                    rightY + 6, ACCENT);
            rightY += ROW_HEIGHT * 2 + 2;
        }

        rightY += 4;
        rightY = heading(graphics, "Roads", column, rightY);
        if (colony.roads().isEmpty()) {
            graphics.drawString(this.font, "no routes remembered yet", column + 4, rightY,
                    DIM, false);
        }
        for (var road : colony.roads()) {
            if (rightY > bottom - ROW_HEIGHT) break;
            String grade = road.grade() + "  " + road.uses() + " trips";
            int gradeX = this.width - MARGIN - this.font.width(grade);
            graphics.drawString(this.font, trim(road.name(), gradeX - column - 12),
                    column + 4, rightY, DIM, false);
            graphics.drawString(this.font, grade, gradeX, rightY,
                    road.dangerous() ? BAD : DIM, false);
            rightY += ROW_HEIGHT;
        }
    }

    // ------------------------------------------------------------------ citizens

    private void drawCitizens(GuiGraphics graphics, ColonySnapshot colony, int top, int bottom) {
        List<CitizenReport> citizens = new ArrayList<>(colony.citizens());
        // Trouble first: the list exists to surface the people who need you.
        citizens.sort((a, b) -> {
            int severity = Integer.compare(severity(b.state()), severity(a.state()));
            return severity != 0 ? severity : a.name().compareTo(b.name());
        });

        int detailWidth = 196;
        int listRight = this.width - MARGIN - detailWidth - 8;
        listTop = top;
        listBottom = bottom;

        int rows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, citizens.size() - rows)));

        visibleRows.clear();
        int y = top;
        for (int i = scroll; i < citizens.size() && y <= bottom - ROW_HEIGHT; i++) {
            CitizenReport citizen = citizens.get(i);
            visibleRows.add(citizen);

            if (citizen.name().equals(selectedCitizen)) {
                graphics.fill(MARGIN - 2, y - 1, listRight, y + ROW_HEIGHT - 2, SELECTED);
            }
            graphics.drawString(this.font, citizen.name(), MARGIN, y, TEXT, false);
            graphics.drawString(this.font, citizen.profession().toLowerCase(Locale.ROOT),
                    MARGIN + 74, y, DIM, false);
            graphics.drawString(this.font, citizen.state().label(), MARGIN + 150, y,
                    colourFor(citizen.state()), false);

            String where = citizen.distanceHome() + "m " + citizen.bearingHome();
            graphics.drawString(this.font, where, MARGIN + 210, y, DIM, false);
            String doing = citizen.problem().isBlank() ? citizen.task() : "! " + citizen.problem();
            graphics.drawString(this.font, trim(doing, listRight - MARGIN - 268),
                    MARGIN + 262, y, citizen.problem().isBlank() ? DIM : BAD, false);
            y += ROW_HEIGHT;
        }

        if (citizens.size() > rows) {
            String more = "scroll — showing " + visibleRows.size() + " of " + citizens.size();
            graphics.drawString(this.font, more, MARGIN, bottom + 2, DIM, false);
        }

        drawCitizenDetail(graphics, findSelected(citizens), listRight + 8, top, bottom);
    }

    private void drawCitizenDetail(GuiGraphics graphics, @Nullable CitizenReport citizen,
                                   int x, int top, int bottom) {
        graphics.fill(x - 4, top - 4, this.width - MARGIN, bottom + 4, PANEL);
        if (citizen == null) {
            graphics.drawString(this.font, "Select a citizen", x, top, DIM, false);
            graphics.drawString(this.font, "to follow or help them.", x, top + ROW_HEIGHT,
                    DIM, false);
            return;
        }

        int y = top;
        graphics.drawString(this.font, citizen.name(), x, y, TEXT, false);
        y += ROW_HEIGHT;
        graphics.drawString(this.font, citizen.profession().toLowerCase(Locale.ROOT)
                + " · " + citizen.state().label(), x, y, colourFor(citizen.state()), false);
        y += ROW_HEIGHT + 3;

        y = detailLine(graphics, "goal", citizen.goal(), x, y, DIM);
        y = detailLine(graphics, "task", citizen.task(), x, y, TEXT);
        if (!citizen.subTask().isBlank()) {
            y = detailLine(graphics, "doing", citizen.subTask(), x, y, DIM);
        }
        if (!citizen.priority().isBlank()) {
            y = detailLine(graphics, "priority", citizen.priority(), x, y, DIM);
        }
        y += 3;
        y = detailLine(graphics, "at", citizen.position().getX() + " " + citizen.position().getY()
                + " " + citizen.position().getZ(), x, y, DIM);
        if (citizen.depthBelowSurface() > 0) {
            y = detailLine(graphics, "depth", citizen.depthBelowSurface() + " below surface",
                    x, y, citizen.depthBelowSurface() > 30 ? WARN : DIM);
        }
        BlockPos destination = citizen.destination();
        if (destination != null) {
            y = detailLine(graphics, "heading to", destination.getX() + " " + destination.getY()
                    + " " + destination.getZ(), x, y, DIM);
        }
        y = detailLine(graphics, "home", citizen.distanceHome() + "m " + citizen.bearingHome(),
                x, y, DIM);
        y += 3;
        y = detailLine(graphics, "health", String.format("%.0f", citizen.health()), x, y,
                citizen.health() < 8 ? BAD : DIM);
        y = detailLine(graphics, "hunger", String.format("%.0f", citizen.hunger()), x, y,
                citizen.hunger() < 25 ? WARN : DIM);
        y += 3;

        if (!citizen.reason().isBlank()) {
            y = wrapped(graphics, "because", citizen.reason(), x, y, DIM, bottom);
        }
        if (!citizen.problem().isBlank()) {
            y = detailLine(graphics, "problem", citizen.problem()
                    + " (" + citizen.stuckTicks() / 20 + "s)", x, y, BAD);
        }
        if (!citizen.fallback().isBlank()) {
            y = wrapped(graphics, "fallback", citizen.fallback(), x, y, WARN, bottom);
        }
        if (!citizen.lastSuccess().isBlank()) {
            y = detailLine(graphics, "last done", citizen.lastSuccess(), x, y, DIM);
        }
        y = detailLine(graphics, "completed", citizen.completedCount() + " tasks", x, y, DIM);
        y += 3;
        wrapped(graphics, "carrying", citizen.inventory(), x, y, DIM, bottom);
    }

    // ------------------------------------------------------------------ map

    private void drawMap(GuiGraphics graphics, ColonySnapshot colony, int top, int bottom) {
        BlockPos viewer = this.minecraft == null || this.minecraft.player == null
                ? null : this.minecraft.player.blockPosition();

        int available = Math.min(this.width - MARGIN * 2, bottom - top);
        int cells = 61;
        int cellSize = Math.max(2, available / cells);
        int size = cellSize * cells;
        int originX = (this.width - size) / 2;
        int originY = top + Math.max(0, (bottom - top - size) / 2);

        MiniMap map = MiniMap.of(colony, viewer, cells, cells);
        graphics.fill(originX - 2, originY - 2, originX + size + 2, originY + size + 2, PANEL);

        for (int row = 0; row < cells; row++) {
            for (int column = 0; column < cells; column++) {
                MiniMap.Cell cell = map.at(column, row);
                if (cell == MiniMap.Cell.EMPTY) continue;
                int px = originX + column * cellSize;
                int py = originY + row * cellSize;
                graphics.fill(px, py, px + cellSize, py + cellSize, colourFor(cell));
            }
        }

        String scale = "1 square = " + map.scale() + " blocks · north is up";
        graphics.drawString(this.font, scale,
                (this.width - this.font.width(scale)) / 2, originY + size + 6, DIM, false);

        // A key, so the colours mean something without a manual.
        int legendY = top;
        for (MiniMap.Cell cell : MiniMap.Cell.values()) {
            if (cell == MiniMap.Cell.EMPTY || cell == MiniMap.Cell.EXPLORED) continue;
            graphics.fill(MARGIN, legendY + 1, MARGIN + 7, legendY + 8, colourFor(cell));
            graphics.drawString(this.font, cell.label, MARGIN + 11, legendY, DIM, false);
            legendY += ROW_HEIGHT;
        }
    }

    // ------------------------------------------------------------------ events

    private void drawEvents(GuiGraphics graphics, ColonySnapshot colony, int top, int bottom) {
        List<ColonyEventLog.Entry> events = new ArrayList<>(colony.events());
        java.util.Collections.reverse(events);   // newest first, like any feed

        int rows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, events.size() - rows)));

        if (events.isEmpty()) {
            graphics.drawString(this.font, "Nothing has happened yet.", MARGIN, top, DIM, false);
            return;
        }
        int y = top;
        for (int i = scroll; i < events.size() && y <= bottom - ROW_HEIGHT; i++) {
            ColonyEventLog.Entry event = events.get(i);
            long agoSeconds = Math.max(0, (colony.gameTime() - event.gameTime()) / 20);
            String when = agoSeconds < 60 ? agoSeconds + "s"
                    : agoSeconds < 3600 ? (agoSeconds / 60) + "m" : (agoSeconds / 3600) + "h";
            graphics.drawString(this.font, when, MARGIN, y, DIM, false);
            graphics.drawString(this.font, event.kind().label(), MARGIN + 34, y,
                    colourFor(event.kind()), false);
            graphics.drawString(this.font,
                    trim(event.line(), this.width - MARGIN * 2 - 130), MARGIN + 124, y,
                    TEXT, false);
            y += ROW_HEIGHT;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.CITIZENS && mouseY >= listTop && mouseY <= listBottom) {
            int index = (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < visibleRows.size()) {
                String clicked = visibleRows.get(index).name();
                // Clicking the selected row again clears it, which is the only
                // way to get back to "nobody selected".
                selectedCitizen = clicked.equals(selectedCitizen) ? null : clicked;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.CITIZENS || tab == Tab.EVENTS) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        // Tab cycles the tabs, which is what everyone tries first.
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            Tab[] tabs = Tab.values();
            tab = tabs[(tab.ordinal() + 1) % tabs.length];
            scroll = 0;
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private CitizenReport findSelected(List<CitizenReport> citizens) {
        if (selectedCitizen == null) return null;
        for (CitizenReport citizen : citizens) {
            if (selectedCitizen.equals(citizen.name())) return citizen;
        }
        return null;
    }

    private int heading(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(this.font, text, x, y, ACCENT, false);
        int rule = Math.min(this.width - MARGIN, x + Math.max(150, this.font.width(text) + 24));
        graphics.fill(x, y + ROW_HEIGHT - 2, rule, y + ROW_HEIGHT - 1, LINE);
        return y + ROW_HEIGHT + 2;
    }

    private int keyValue(GuiGraphics graphics, String key, String value, int x, int y) {
        graphics.drawString(this.font, key, x + 4, y, DIM, false);
        graphics.drawString(this.font, value, x + 120, y, TEXT, false);
        return y + ROW_HEIGHT;
    }

    private int detailLine(GuiGraphics graphics, String key, String value, int x, int y,
                           int colour) {
        graphics.drawString(this.font, key, x, y, DIM, false);
        graphics.drawString(this.font, trim(value, this.width - MARGIN - x - 58),
                x + 58, y, colour, false);
        return y + ROW_HEIGHT;
    }

    /** A value too long for one line, broken across as many as it needs. */
    private int wrapped(GuiGraphics graphics, String key, String value, int x, int y,
                        int colour, int bottom) {
        graphics.drawString(this.font, key, x, y, DIM, false);
        int width = this.width - MARGIN - x;
        for (var line : this.font.split(Component.literal(value), width)) {
            if (y > bottom - ROW_HEIGHT) break;
            y += ROW_HEIGHT;
            graphics.drawString(this.font, line, x + 4, y, colour, false);
        }
        return y + ROW_HEIGHT + 2;
    }

    private void centred(GuiGraphics graphics, String text, int colour) {
        graphics.drawString(this.font, text, (this.width - this.font.width(text)) / 2,
                this.height / 2, colour, false);
    }

    private String trim(String text, int maxWidth) {
        if (maxWidth <= 8 || this.font.width(text) <= maxWidth) return text;
        return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("…")) + "…";
    }

    private static String shortName(String itemId) {
        return itemId == null ? "" : itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
    }

    private static int severity(ProductivityMonitor.State state) {
        return switch (state) {
            case LOST -> 3;
            case STUCK -> 2;
            case IDLE -> 1;
            default -> 0;
        };
    }

    private static int colourFor(ProductivityMonitor.State state) {
        return switch (state) {
            case LOST, STUCK -> BAD;
            case IDLE -> WARN;
            case FIGHTING -> 0xFFE89A6A;
            case DEAD -> DIM;
            default -> GOOD;
        };
    }

    private static int colourFor(ColonyEventLog.Kind kind) {
        return switch (kind) {
            case DANGER, RESCUE -> BAD;
            case CONSTRUCTION -> WARN;
            case DISCOVERY -> ACCENT;
            case INFRASTRUCTURE -> 0xFFC9B46A;
            case POPULATION -> GOOD;
            default -> DIM;
        };
    }

    private static int colourFor(MiniMap.Cell cell) {
        return switch (cell) {
            case TOWN_HALL -> 0xFFFFD27B;
            case BUILDING -> 0xFFB9A88A;
            case PROJECT -> 0xFFE8C46A;
            case STORAGE -> 0xFF9AC4E8;
            case FARM -> 0xFF9AD17B;
            case FOREST -> 0xFF4E8C4E;
            case MINE -> 0xFFA98BC4;
            case DISTRICT -> 0xFF6E7681;
            case ROAD -> 0xFF5A5F66;
            case CITIZEN -> 0xFF7BC4E8;
            case TROUBLE -> BAD;
            case VIEWER -> 0xFFFFFFFF;
            default -> 0xFF2A2F36;
        };
    }
}
