package mvdicarlo.trialbound.clog;

import java.util.Set;

import lombok.Value;

/** One collection log page (e.g. a boss) and the item ids it contains. */
@Value
public class ClogPage {
    /** Display name exactly as it appears in the cache, e.g. "Dagannoth Kings". */
    String name;

    /** Normalized name (ClogText.normalize) used for lookups. */
    String normalizedName;

    ClogTab tab;

    /** Item ids on this page, after replacement-enum substitution. Immutable. */
    Set<Integer> itemIds;
}
