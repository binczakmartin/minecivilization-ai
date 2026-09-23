package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Writing on the colony's signs.
 *
 * <p>A settlement that labels itself is one a player can read without opening a
 * command. Every district, every building and every level of the mine gets a
 * sign saying what it is, so the place explains itself from inside the world
 * rather than only through {@code /mciv}.</p>
 *
 * <p>The wrapping is the fiddly part — a sign is four short lines, and text
 * that overruns is silently cut off — so the line-breaking is pure and
 * tested.</p>
 */
public final class Signpost {

    /** Lines on one side of a sign. */
    public static final int LINES = 4;
    /** Characters that fit on a line before it is cut off. */
    public static final int LINE_WIDTH = 15;

    private Signpost() {
    }

    /**
     * Fit a title and a detail onto the four lines of a sign.
     *
     * <p>The title is wrapped across as many lines as it needs and the detail
     * takes whatever is left, because a district's name matters more than its
     * coordinates.</p>
     */
    public static List<String> layout(String title, String detail) {
        List<String> lines = new ArrayList<>(wrap(title, LINES));
        int remaining = LINES - lines.size();
        if (remaining > 0 && detail != null && !detail.isBlank()) {
            lines.addAll(wrap(detail, remaining));
        }
        while (lines.size() < LINES) lines.add("");
        return lines.subList(0, LINES);
    }

    /** Break text onto at most {@code maxLines} lines, on word boundaries where it can. */
    static List<String> wrap(String text, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxLines <= 0) return lines;

        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            // A word longer than a line is cut rather than dropped: a truncated
            // label still tells you more than a blank sign.
            while (word.length() > LINE_WIDTH) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    if (lines.size() >= maxLines) return lines;
                }
                lines.add(word.substring(0, LINE_WIDTH));
                word = word.substring(LINE_WIDTH);
                if (lines.size() >= maxLines) return lines;
            }
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= LINE_WIDTH) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
                if (lines.size() >= maxLines) return lines;
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** A readable name for a zone, e.g. {@code FARM} to "Farmland". */
    public static String titleFor(ZoneType type) {
        String label = type.label();
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    /** A readable name for an ore, e.g. {@code minecraft:iron_ore} to "Iron". */
    public static String titleForOre(String itemId) {
        if (itemId == null) return "Ore";
        String name = itemId.substring(itemId.indexOf(':') + 1)
                .replace("deepslate_", "").replace("_ore", "").replace("raw_", "")
                .replace('_', ' ');
        if (name.isEmpty()) return "Ore";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ world

    /**
     * Write onto a sign already standing at {@code pos}.
     *
     * @return true when a sign was there and took the text
     */
    public static boolean write(ServerLevel level, BlockPos pos, String title, String detail) {
        if (level == null || pos == null) return false;
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false;

        List<String> lines = layout(title, detail);
        SignText text = sign.getFrontText();
        for (int i = 0; i < LINES; i++) {
            text = text.setMessage(i, Component.literal(lines.get(i)));
        }
        sign.setText(text, true);
        sign.setText(text, false);   // both faces, so it reads from either side
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        sign.setChanged();
        return true;
    }
}
