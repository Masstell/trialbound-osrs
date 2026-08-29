package mvdicarlo.crabmanmode.enforcement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether two items in the same RuneLite variation family are the
 * SAME logical unlock (charge/degrade states of one item) or DISTINCT
 * unlockables that merely share a family.
 *
 * The variation mapping groups both kinds: "Uncharged trident" sits with
 * "Trident of the Seas (full)" (one unlock, several charge states), but
 * plain "Rune platebody" also sits with the clue-reward "Rune platebody
 * (g)/(t)/(h1..h5)" (each its own collection log slot), "Godsword shard 1"
 * with shards 2 and 3, and "Shayzien gloves (1)" with tiers (2)..(5).
 * Treating the whole family as one identity both over-locks (plain rune,
 * mystic, d'hide gear locked because their trimmed clue variants are) and
 * over-unlocks (buying the (g) variant would free the (t) and (h) ones).
 */
public final class ItemIdentity {
    /**
     * Suffixes that describe a state of the SAME item rather than a
     * different item: charge, degradation, trouver-locked, corruption...
     */
    private static final Set<String> STATE_SUFFIXES = new HashSet<>(Arrays.asList(
            "uncharged", "charged", "empty", "full", "inert", "broken", "damaged",
            "l", "u", "c", "open", "closed", "active", "inactive", "lit", "unlit"));

    /**
     * True when an unlock of {@code nameB} should count for {@code nameA}
     * (and vice versa - the relation is symmetric).
     *
     * <ul>
     * <li>Identical names always share (Graceful recolours, Chompy bird
     * hats - indistinguishable by name anyway).</li>
     * <li>Different base names share too: that is the classic charge family
     * where charging renames the item (Uncharged trident / Trident of the
     * Seas).</li>
     * <li>Same base name: share only when the differing suffixes are pure
     * state ((uncharged), (broken), (l)...). Cosmetic suffixes ((g), (t),
     * (h1), (light), (Guthix)...) mark distinct unlockables.</li>
     * <li>Numeric suffixes are charges on a single item (Black mask (10),
     * Ahrim's hood 100) unless BOTH sides are clog identities, where they
     * enumerate distinct slots (Godsword shard 1/2/3, Shayzien gloves
     * (1)..(5), Victor's cape (n)).</li>
     * </ul>
     */
    public static boolean sharesIdentity(String nameA, String nameB, boolean bothClogIdentities) {
        if (nameA == null || nameB == null) {
            return false;
        }
        if (nameA.equalsIgnoreCase(nameB)) {
            return true;
        }
        Parsed a = parse(nameA);
        Parsed b = parse(nameB);
        if (!a.base.equalsIgnoreCase(b.base)) {
            return true;
        }
        for (String suffix : symmetricDifference(a.suffixes, b.suffixes)) {
            if (!isStateSuffix(suffix, bothClogIdentities)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStateSuffix(String suffix, boolean bothClogIdentities) {
        if (isNumeric(suffix)) {
            return !bothClogIdentities;
        }
        return STATE_SUFFIXES.contains(suffix);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Base name plus the trailing suffix tokens, lowercased. */
    private static Parsed parse(String name) {
        String base = name.toLowerCase().trim();
        List<String> suffixes = new ArrayList<>(2);
        while (true) {
            if (base.endsWith(")")) {
                int open = base.lastIndexOf(" (");
                if (open > 0 && base.indexOf('(', open + 2) < 0) {
                    suffixes.add(base.substring(open + 2, base.length() - 1).trim());
                    base = base.substring(0, open).trim();
                    continue;
                }
            }
            int lastSpace = base.lastIndexOf(' ');
            if (lastSpace > 0 && isNumeric(base.substring(lastSpace + 1))) {
                // Bare trailing number: "Ahrim's hood 100", "Godsword shard 1".
                suffixes.add(base.substring(lastSpace + 1));
                base = base.substring(0, lastSpace).trim();
                continue;
            }
            break;
        }
        return new Parsed(base, suffixes);
    }

    /** Multiset symmetric difference of the two suffix lists. */
    private static List<String> symmetricDifference(List<String> a, List<String> b) {
        List<String> diff = new ArrayList<>(a);
        List<String> remaining = new ArrayList<>(b);
        for (String s : b) {
            if (!diff.remove(s)) {
                continue;
            }
            remaining.remove(s);
        }
        diff.addAll(remaining);
        return diff;
    }

    private static final class Parsed {
        final String base;
        final List<String> suffixes;

        Parsed(String base, List<String> suffixes) {
            this.base = base;
            this.suffixes = suffixes;
        }
    }

    private ItemIdentity() {
    }
}
