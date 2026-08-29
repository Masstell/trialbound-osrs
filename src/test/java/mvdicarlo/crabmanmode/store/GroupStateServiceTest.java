package mvdicarlo.crabmanmode.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.Gson;

import mvdicarlo.crabmanmode.SessionState;

public class GroupStateServiceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TbEventStore store;
    private SessionState sessionState;
    private GroupStateService service;

    @Before
    public void setUp() {
        store = new TbEventStore(new Gson(), new File(tmp.getRoot(), "trialbound"));
        sessionState = new SessionState();
        sessionState.setEnabledCharacter("Matt");
        sessionState.setCurrentCharacter("Matt");
        service = new GroupStateService(store, sessionState);
        service.initialize("testgroup");
    }

    private static TbEventRecord unlock(String id, int itemId, String player, long at, UnlockSource source,
            Integer cost) {
        return new TbEventRecord(id, TbEventKind.UNLOCK, player, at, itemId, "Item" + itemId, source, cost,
                null, null, null, null);
    }

    private static TbEventRecord grit(String id, String player, long at, int delta, GritReason reason,
            String trialKey) {
        return new TbEventRecord(id, TbEventKind.GRIT, player, at, null, null, null, null, delta, reason,
                trialKey, null);
    }

    private static TbEventRecord relockEvent(String id, int itemId, String player, long at) {
        return new TbEventRecord(id, TbEventKind.RELOCK, player, at, itemId, null, null, null, null, null, null, null);
    }

    @Test
    public void balancesSumPerPlayerAndPooled() {
        service.mergeRemote(Arrays.asList(
                grit("a", "Matt", 1000, 75, GritReason.TRIAL_DROP, "2026-08-28:DAILY"),
                grit("b", "Alice", 2000, 50, GritReason.TRIAL_DROP, "2026-W35:WEEKLY_HARD"),
                grit("c", "Matt", 3000, 30, GritReason.TRIAL_DROP, "2026-08-28:DAILY")));
        assertEquals(105, service.getGritBalance("Matt"));
        assertEquals(50, service.getGritBalance("Alice"));
        assertEquals(155, service.getPooledGrit());
        assertEquals(105, service.getGritEarnedForTrial("2026-08-28:DAILY"));
    }

    @Test
    public void purchaseRaceResolvesToEarliestAndRefundsLoser() {
        // Two members bought item 4151 while offline from each other; both event
        // pairs use the deterministic ids, so merging keeps exactly one pair.
        List<TbEventRecord> mattPurchase = Arrays.asList(
                unlock("purchase-4151", 4151, "Matt", 5000, UnlockSource.PURCHASE, 500),
                grit("purchase-grit-4151", "Matt", 5000, -500, GritReason.PURCHASE, null));
        List<TbEventRecord> alicePurchase = Arrays.asList(
                unlock("purchase-4151", 4151, "Alice", 4000, UnlockSource.PURCHASE, 500),
                grit("purchase-grit-4151", "Alice", 4000, -500, GritReason.PURCHASE, null));

        service.mergeRemote(mattPurchase);
        service.mergeRemote(alicePurchase);

        // Alice was earlier: she wins; Matt's spend disappears (refund).
        assertEquals("Alice", service.getUnlockedItems().get(4151).getPlayer());
        assertEquals(-500, service.getGritBalance("Alice"));
        assertEquals(0, service.getGritBalance("Matt"));

        // Order independence: replaying the other way converges identically.
        GroupStateService other = new GroupStateService(
                new TbEventStore(new Gson(), new File(tmp.getRoot(), "other")), sessionState);
        other.initialize("othergroup");
        other.mergeRemote(alicePurchase);
        other.mergeRemote(mattPurchase);
        assertEquals("Alice", other.getUnlockedItems().get(4151).getPlayer());
        assertEquals(0, other.getGritBalance("Matt"));
    }

    @Test
    public void relockTombstoneExcludesEarlierUnlocksOnly() {
        service.mergeRemote(Collections.singletonList(
                unlock("u1", 11832, "Matt", 1000, UnlockSource.DROP, null)));
        assertTrue(service.isUnlocked(11832));

        service.mergeRemote(Collections.singletonList(relockEvent("r1", 11832, "Matt", 2000)));
        assertFalse(service.isUnlocked(11832));

        // A fresh drop after the relock unlocks the item again.
        service.mergeRemote(Collections.singletonList(
                unlock("u2", 11832, "Alice", 3000, UnlockSource.DROP, null)));
        assertTrue(service.isUnlocked(11832));
        assertEquals("Alice", service.getUnlockedItems().get(11832).getPlayer());
    }

    @Test
    public void repurchaseAfterRelockUnlocksAgain() {
        service.addAdminGrit(1000);
        assertEquals(PurchaseResult.SUCCESS, service.purchase(11832, "Bandos chestplate", 300));
        assertTrue(service.isUnlocked(11832));

        service.relock(11832);
        assertFalse(service.isUnlocked(11832));

        // The new purchase pair gets generation-suffixed ids, so it does not
        // collide with (and lose to) the tombstoned original purchase.
        assertEquals(PurchaseResult.SUCCESS, service.purchase(11832, "Bandos chestplate", 300));
        assertTrue(service.isUnlocked(11832));
        assertEquals(400, service.getPooledGrit()); // charged both times, no refund on relock
    }

    @Test
    public void relockedPurchaseHistoryConvergesForLatecomers() {
        // A peer that was offline for the whole purchase -> relock -> repurchase
        // history must converge to "unlocked" regardless of merge order.
        List<TbEventRecord> history = Arrays.asList(
                unlock("purchase-4151", 4151, "Matt", 1000, UnlockSource.PURCHASE, 300),
                grit("purchase-grit-4151", "Matt", 1000, -300, GritReason.PURCHASE, null),
                relockEvent("r1", 4151, "Matt", 2000),
                unlock("purchase-4151-g1", 4151, "Alice", 3000, UnlockSource.PURCHASE, 300),
                grit("purchase-grit-4151-g1", "Alice", 3000, -300, GritReason.PURCHASE, null));

        service.mergeRemote(history);
        assertTrue(service.isUnlocked(4151));
        assertEquals("Alice", service.getUnlockedItems().get(4151).getPlayer());
        assertEquals(-600, service.getPooledGrit());

        // Reversed order converges identically.
        GroupStateService other = new GroupStateService(
                new TbEventStore(new Gson(), new File(tmp.getRoot(), "reversed")), sessionState);
        other.initialize("reversedgroup");
        for (int i = history.size() - 1; i >= 0; i--) {
            other.mergeRemote(Collections.singletonList(history.get(i)));
        }
        assertTrue(other.isUnlocked(4151));
        assertEquals("Alice", other.getUnlockedItems().get(4151).getPlayer());
    }

    @Test
    public void purchaseSurvivesRelockWithFutureTimestamp() {
        // A teammate's relock can carry a createdOn ahead of our clock. A
        // deliberate re-purchase is stamped after it instead of being
        // instantly tombstoned with its spend orphaned.
        service.mergeRemote(Collections.singletonList(
                relockEvent("r1", 11832, "Alice", System.currentTimeMillis() + 120_000)));
        service.addAdminGrit(1000);

        assertEquals(PurchaseResult.SUCCESS, service.purchase(11832, "Bandos chestplate", 300));
        assertTrue(service.isUnlocked(11832));
        assertEquals(700, service.getPooledGrit());
    }

    @Test
    public void divergedGenerationPurchaseRaceRefundsLoser() {
        // Peers whose relock histories diverged mint non-colliding purchase
        // ids for the same item. The projection settles it: only the winning
        // unlock's spend counts, so the pool is charged once.
        List<TbEventRecord> history = Arrays.asList(
                relockEvent("r1", 4151, "Matt", 2000),
                unlock("purchase-4151-g1", 4151, "Alice", 3000, UnlockSource.PURCHASE, 300),
                grit("purchase-grit-4151-g1", "Alice", 3000, -300, GritReason.PURCHASE, null),
                // Matt never merged r1: stale generation-0 ids, later stamp.
                unlock("purchase-4151", 4151, "Matt", 3500, UnlockSource.PURCHASE, 300),
                grit("purchase-grit-4151", "Matt", 3500, -300, GritReason.PURCHASE, null));

        service.mergeRemote(history);
        assertTrue(service.isUnlocked(4151));
        assertEquals("Alice", service.getUnlockedItems().get(4151).getPlayer());
        assertEquals(-300, service.getPooledGrit());
        assertEquals(0, service.getGritBalance("Matt"));

        // Reversed order converges identically.
        GroupStateService other = new GroupStateService(
                new TbEventStore(new Gson(), new File(tmp.getRoot(), "diverged")), sessionState);
        other.initialize("divergedgroup");
        for (int i = history.size() - 1; i >= 0; i--) {
            other.mergeRemote(Collections.singletonList(history.get(i)));
        }
        assertEquals(-300, other.getPooledGrit());
        assertEquals("Alice", other.getUnlockedItems().get(4151).getPlayer());
    }

    @Test
    public void dropBeatingPurchaseRefundsTheSpend() {
        // A drop that merges with an earlier stamp takes the item; paying
        // for what the group already found by drop is refunded by omission.
        service.mergeRemote(Arrays.asList(
                unlock("purchase-4151", 4151, "Matt", 2000, UnlockSource.PURCHASE, 300),
                grit("purchase-grit-4151", "Matt", 2000, -300, GritReason.PURCHASE, null)));
        assertEquals(-300, service.getPooledGrit());

        service.mergeRemote(Collections.singletonList(
                unlock("u-drop", 4151, "Alice", 1000, UnlockSource.DROP, null)));
        assertEquals("Alice", service.getUnlockedItems().get(4151).getPlayer());
        assertEquals(0, service.getPooledGrit());
    }

    @Test
    public void sameMillisecondRelockTombstonesTheUnlock() {
        service.mergeRemote(Collections.singletonList(
                unlock("u1", 11832, "Matt", 2000, UnlockSource.DROP, null)));
        service.mergeRemote(Collections.singletonList(relockEvent("r1", 11832, "Matt", 2000)));
        assertFalse(service.isUnlocked(11832));
    }

    @Test
    public void earliestDropWinsAttribution() {
        service.mergeRemote(Arrays.asList(
                unlock("u1", 20997, "Matt", 2000, UnlockSource.DROP, null),
                unlock("u2", 20997, "Alice", 1000, UnlockSource.DROP, null)));
        assertEquals("Alice", service.getUnlockedItems().get(20997).getPlayer());
    }

    @Test
    public void localIntentsPersistAcrossReload() {
        service.unlockDrop(4151, "Abyssal whip");
        service.addTrialGrit(4151, 75, "2026-08-28:DAILY", 300);
        assertEquals(PurchaseResult.INSUFFICIENT_GRIT, service.purchase(11832, "Bandos chestplate", 500));
        assertEquals(PurchaseResult.SUCCESS, service.purchase(11832, "Bandos chestplate", 50));
        assertEquals(PurchaseResult.ALREADY_UNLOCKED, service.purchase(11832, "Bandos chestplate", 50));

        // The single-thread writer needs a moment to flush.
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }

        GroupStateService reloaded = new GroupStateService(store, sessionState);
        reloaded.initialize("testgroup");
        assertTrue(reloaded.isUnlocked(4151));
        assertTrue(reloaded.isUnlocked(11832));
        assertEquals(25, reloaded.getPooledGrit());
        assertEquals(UnlockSource.PURCHASE, reloaded.getUnlockedItems().get(11832).getSource());
        assertEquals((Integer) 50, reloaded.getUnlockedItems().get(11832).getCost());
    }

    @Test
    public void invalidRemoteEventsAreIgnored() {
        int applied = service.mergeRemote(Arrays.asList(
                new TbEventRecord(null, TbEventKind.UNLOCK, "x", 1, 1, null, UnlockSource.DROP, null, null, null,
                        null, null),
                new TbEventRecord("ok", TbEventKind.UNLOCK, "x", 1, null, null, UnlockSource.DROP, null, null, null,
                        null, null)));
        assertEquals(0, applied);
        assertTrue(service.getUnlockedItems().isEmpty());
    }
}
