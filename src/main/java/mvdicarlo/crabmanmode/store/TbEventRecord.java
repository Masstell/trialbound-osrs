package mvdicarlo.crabmanmode.store;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One immutable group-state event: an item unlock, a grit ledger entry, or a
 * relock tombstone. The single wire/disk format for the local JSONL store,
 * party messages, and any future remote backend.
 *
 * Purchases use deterministic ids (purchase-&lt;itemId&gt; and
 * purchase-grit-&lt;itemId&gt;), so two members racing to buy the same unlock
 * produce colliding ids and every peer deterministically keeps one winner -
 * the loser's spend disappears with its event (automatic refund).
 */
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class TbEventRecord {
    /**
     * Total order used to pick a winner between events with the same id:
     * earliest createdOn, then lexicographic player. Identical on every peer.
     */
    public static final Comparator<TbEventRecord> WINNER_ORDER = Comparator
            .comparingLong(TbEventRecord::getCreatedOn)
            .thenComparing(e -> e.getPlayer() == null ? "" : e.getPlayer());

    private String id;
    private TbEventKind kind;
    private String player;
    /** Epoch millis, from the creating client's clock. */
    private long createdOn;

    // UNLOCK / RELOCK (and GRIT attribution)
    private Integer itemId;
    private String itemName;
    private UnlockSource source;
    private Integer cost;

    // GRIT
    private Integer delta;
    private GritReason reason;
    private String trialKey;
    private Integer multiplierPercent;

    public boolean isValid() {
        if (id == null || id.isEmpty() || kind == null || createdOn <= 0) {
            return false;
        }
        switch (kind) {
            case UNLOCK:
                return itemId != null && source != null;
            case GRIT:
                return delta != null && reason != null;
            case RELOCK:
                return itemId != null;
            default:
                return false;
        }
    }

    public static TbEventRecord unlockDrop(int itemId, String itemName, String player, long now) {
        return new TbEventRecord(UUID.randomUUID().toString(), TbEventKind.UNLOCK, player, now,
                itemId, itemName, UnlockSource.DROP, null, null, null, null, null);
    }

    public static TbEventRecord unlockPurchase(int itemId, String itemName, String player, int cost, long now) {
        return new TbEventRecord("purchase-" + itemId, TbEventKind.UNLOCK, player, now,
                itemId, itemName, UnlockSource.PURCHASE, cost, null, null, null, null);
    }

    public static TbEventRecord purchaseSpend(int itemId, String player, int cost, long now) {
        return new TbEventRecord("purchase-grit-" + itemId, TbEventKind.GRIT, player, now,
                itemId, null, null, null, -cost, GritReason.PURCHASE, null, null);
    }

    public static TbEventRecord trialGrit(int itemId, String player, int delta, String trialKey,
            int multiplierPercent, long now) {
        return new TbEventRecord(UUID.randomUUID().toString(), TbEventKind.GRIT, player, now,
                itemId, null, null, null, delta, GritReason.TRIAL_DROP, trialKey, multiplierPercent);
    }

    /** Manual grit adjustment (testing/admin); no item or trial attribution. */
    public static TbEventRecord adminGrit(String player, int delta, long now) {
        return new TbEventRecord(UUID.randomUUID().toString(), TbEventKind.GRIT, player, now,
                null, null, null, null, delta, GritReason.ADMIN, null, null);
    }

    public static TbEventRecord relock(int itemId, String player, long now) {
        return new TbEventRecord(UUID.randomUUID().toString(), TbEventKind.RELOCK, player, now,
                itemId, null, null, null, null, null, null, null);
    }

    public Instant createdInstant() {
        return Instant.ofEpochMilli(createdOn);
    }
}
