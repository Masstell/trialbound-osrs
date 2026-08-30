package mvdicarlo.trialbound.grit;

import mvdicarlo.trialbound.trial.TrialTier;

/**
 * The Grit economy is fixed in code (not config) so every group member runs
 * identical values by construction - the only way to change them is a new
 * build everyone shares. Per-page overrides live in clog_boss_tiers.json,
 * which is bundled in the same jar.
 */
public final class GritEconomy {
    public static int baseGrit(TrialTier tier) {
        switch (tier) {
            case EASY:
                return 10;
            case MEDIUM:
                return 25;
            case HARD:
                return 50;
            case RAID:
                return 50;
            default:
                return 0;
        }
    }

    public static int unlockPrice(TrialTier tier) {
        switch (tier) {
            case EASY:
                return 100;
            case MEDIUM:
                return 250;
            case HARD:
                return 500;
            case RAID:
                return 1000;
            default:
                return 250; // clues, skilling, minigames
        }
    }

    private GritEconomy() {
    }
}
