package mvdicarlo.trialbound.trial;

import java.time.Instant;

import lombok.Value;

/** One active trial: a boss page occupying a trial slot for a period. */
@Value
public class TrialSlot {
    TrialType type;

    /** Raw collection log page name on trial, e.g. "Zulrah". */
    String pageName;

    /** Canonical period key, e.g. "2026-08-28" / "2026-W35" / "2026-08". */
    String periodKey;

    /** UTC instant this trial rolls over. */
    Instant periodEndUtc;

    /** Stored on grit events; identical across all group members. */
    public String trialKey() {
        return periodKey + ":" + type.name();
    }

    public int getMultiplierPercent() {
        return type.getMultiplierPercent();
    }
}
