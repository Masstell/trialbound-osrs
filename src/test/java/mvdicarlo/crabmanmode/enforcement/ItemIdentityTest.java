package mvdicarlo.crabmanmode.enforcement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Real item names from the variation families the collection log actually
 * exercises; every case here is a family RuneLite groups together.
 */
public class ItemIdentityTest {
    private static void shares(String a, String b, boolean bothClog) {
        assertTrue(a + " should share identity with " + b, ItemIdentity.sharesIdentity(a, b, bothClog));
        assertTrue(b + " should share identity with " + a, ItemIdentity.sharesIdentity(b, a, bothClog));
    }

    private static void distinct(String a, String b, boolean bothClog) {
        assertFalse(a + " should NOT share identity with " + b, ItemIdentity.sharesIdentity(a, b, bothClog));
        assertFalse(b + " should NOT share identity with " + a, ItemIdentity.sharesIdentity(b, a, bothClog));
    }

    @Test
    public void chargeStatesShare() {
        // Different-name charge family.
        shares("Trident of the seas", "Uncharged trident", false);
        shares("Trident of the Seas (full)", "Uncharged trident", true);
        // Same-name charge suffixes.
        shares("Dizana's quiver", "Dizana's quiver (uncharged)", false);
        shares("Pharaoh's sceptre", "Pharaoh's sceptre (uncharged)", false);
        shares("Toxic blowpipe", "Toxic blowpipe (empty)", false);
        shares("Dragon pickaxe", "Dragon pickaxe (broken)", true);
        // Trouver-locked variants are the same item.
        shares("Dizana's quiver (l)", "Dizana's quiver (uncharged)", false);
        // Numeric charges on a single clog identity.
        shares("Black mask", "Black mask (10)", false);
        shares("Black mask (4)", "Black mask (10)", false);
        shares("Ahrim's hood 100", "Ahrim's hood", false);
        // Corruption is a state, not a different unlock.
        shares("Blade of saeldor (c)", "Blade of saeldor (inactive)", false);
        // Imbues are states of the same unlock.
        shares("Black mask (i)", "Black mask (10)", false);
        shares("Black mask (10) (i)", "Black mask (10)", false);
        shares("Berserker ring (i)", "Berserker ring", false);
        shares("Ring of the gods (i)", "Ring of the gods", false);
        // Trimmed charge suffixes: (t)/(t1)..(t4) drain states of the (t4)
        // Treasure Trails glory, distinct from every plain glory.
        shares("Amulet of glory (t)", "Amulet of glory (t4)", false);
        shares("Amulet of glory (t1)", "Amulet of glory (t4)", false);
        // A renamed charge/upgrade form still bridges different bases when a
        // state word is present on either side.
        shares("Blessed dizana's quiver", "Dizana's quiver (uncharged)", false);
    }

    @Test
    public void identicalNamesShare() {
        shares("Graceful hood", "Graceful hood", true);
        shares("Chompy bird hat", "Chompy bird hat", true);
    }

    @Test
    public void clueCosmeticsAreDistinctUnlocks() {
        // The reported bug: plain gear locked by its trimmed clue variants.
        distinct("Rune platebody", "Rune platebody (g)", false);
        distinct("Rune platebody", "Rune platebody (t)", false);
        distinct("Rune platebody", "Rune platebody (h1)", false);
        distinct("Mystic hat", "Mystic hat (dark)", false);
        distinct("Mystic robe top", "Mystic robe top (dusk)", false);
        distinct("Blue d'hide body", "Blue d'hide body (g)", false);
        distinct("Adamant platebody", "Adamant platebody (t)", false);
        distinct("Mithril full helm", "Mithril full helm (g)", false);
        // And between two clue variants of the same base.
        distinct("Rune platebody (g)", "Rune platebody (t)", true);
        distinct("Rune platebody (g)", "Rune platebody (h3)", true);
        distinct("Mystic hat (light)", "Mystic hat (dark)", true);
        distinct("Bucket helm", "Bucket helm (g)", true);
        distinct("Rune scimitar ornament kit (Guthix)", "Rune scimitar ornament kit (Zamorak)", true);
    }

    @Test
    public void differentBasesWithoutStateMarkersAreDistinct() {
        // Same variation family, but each its own collection log slot: the
        // eternal glory must not merge with the trimmed or plain glories.
        distinct("Amulet of eternal glory", "Amulet of glory (t4)", true);
        distinct("Amulet of eternal glory", "Amulet of glory", false);
        distinct("Amulet of glory", "Amulet of glory (t4)", false);
        distinct("Void knight top", "Elite void top", true);
    }

    @Test
    public void numberedClogSiblingsAreDistinct() {
        distinct("Godsword shard 1", "Godsword shard 2", true);
        distinct("Saradomin page 1", "Saradomin page 3", true);
        distinct("Shayzien gloves (1)", "Shayzien gloves (2)", true);
        distinct("Sinhaza shroud tier 1", "Sinhaza shroud tier 2", true);
        distinct("Victor's cape (1)", "Victor's cape (500)", true);
        distinct("Ancient page", "Ancient page 1", true);
    }
}
