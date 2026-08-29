package mvdicarlo.trialbound.trial;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;

public class TrialServiceTest {
    private final BossTierRegistry registry = new BossTierRegistry(new Gson());

    @Test
    public void periodKeysAreCanonical() {
        LocalDate date = LocalDate.of(2026, 8, 28);
        assertEquals("2026-08-28", TrialService.dailyKey(date));
        assertEquals("2026-W35", TrialService.weeklyKey(date));
        assertEquals("2026-08", TrialService.monthlyKey(date));
    }

    @Test
    public void weeklyKeyUsesWeekBasedYearAtBoundaries() {
        // Monday 2024-12-30 belongs to week 1 of ISO year 2025.
        assertEquals("2025-W01", TrialService.weeklyKey(LocalDate.of(2024, 12, 30)));
        // Friday 2027-01-01 belongs to week 53 of ISO year 2026.
        assertEquals("2026-W53", TrialService.weeklyKey(LocalDate.of(2027, 1, 1)));
        assertEquals("2026-W01", TrialService.weeklyKey(LocalDate.of(2026, 1, 1)));
    }

    @Test
    public void periodEndsAreUtcBoundaries() {
        LocalDate friday = LocalDate.of(2026, 8, 28);
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"), TrialService.dailyEnd(friday));
        assertEquals(Instant.parse("2026-08-31T00:00:00Z"), TrialService.weeklyEnd(friday));
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), TrialService.monthlyEnd(friday));
        // A Monday's weekly trial ends the FOLLOWING Monday, not today.
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), TrialService.weeklyEnd(LocalDate.of(2026, 8, 31)));
    }

    @Test
    public void pickIndexIsDeterministicAndInRange() {
        int first = TrialService.pickIndex("2026-08-28", "daily", 57);
        int second = TrialService.pickIndex("2026-08-28", "daily", 57);
        assertEquals(first, second);
        assertTrue(first >= 0 && first < 57);
        // Different salts and periods should be free to differ (not asserting
        // inequality - just that they are valid indices).
        assertTrue(TrialService.pickIndex("2026-08-28", "weekly-easy", 18) >= 0);
        assertTrue(TrialService.pickIndex("2026-08-29", "daily", 57) < 57);
    }

    @Test
    public void computeTrialsIsDeterministicAndWellFormed() {
        LocalDate date = LocalDate.of(2026, 8, 28);
        List<TrialSlot> first = TrialService.computeTrials(date, registry);
        List<TrialSlot> second = TrialService.computeTrials(date, registry);
        assertEquals(first, second);
        assertEquals(5, first.size());

        for (TrialSlot slot : first) {
            switch (slot.getType()) {
                case DAILY:
                    assertEquals("2026-08-28", slot.getPeriodKey());
                    break;
                case WEEKLY_EASY:
                    assertTrue(registry.getPages(TrialTier.EASY).contains(slot.getPageName()));
                    assertEquals("2026-W35", slot.getPeriodKey());
                    break;
                case WEEKLY_MEDIUM:
                    assertTrue(registry.getPages(TrialTier.MEDIUM).contains(slot.getPageName()));
                    break;
                case WEEKLY_HARD:
                    assertTrue(registry.getPages(TrialTier.HARD).contains(slot.getPageName()));
                    break;
                case MONTHLY:
                    assertTrue(registry.getPages(TrialTier.RAID).contains(slot.getPageName()));
                    assertEquals("2026-08", slot.getPeriodKey());
                    break;
            }
            assertEquals(slot.getPeriodKey() + ":" + slot.getType().name(), slot.trialKey());
            assertFalse(slot.getPageName().isEmpty());
        }
    }

    @Test
    public void tierFileLoadsAllTiers() {
        assertFalse(registry.getPages(TrialTier.EASY).isEmpty());
        assertFalse(registry.getPages(TrialTier.MEDIUM).isEmpty());
        assertFalse(registry.getPages(TrialTier.HARD).isEmpty());
        assertEquals(3, registry.getPages(TrialTier.RAID).size());
        // Alias resolution is deterministic and case/format-insensitive.
        assertEquals("Dagannoth Kings", registry.resolveAlias("Dagannoth  Rex").get());
        assertEquals("The Nightmare", registry.resolveAlias("Phosani's Nightmare").get());
        assertEquals(TrialTier.RAID, registry.getTier("Chambers of Xeric"));
        assertEquals(TrialTier.NON_BOSS, registry.getTier("Aerial Fishing"));
    }
}
