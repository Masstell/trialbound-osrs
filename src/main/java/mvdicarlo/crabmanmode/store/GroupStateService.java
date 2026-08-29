package mvdicarlo.crabmanmode.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;

/**
 * Local-first group state: an in-memory event map + projections backed by the
 * append-only JSONL store. Every mutation is an event; merges from transports
 * go through the same deterministic rules, so all peers converge on identical
 * unlocks and balances.
 */
@Slf4j
@Singleton
public class GroupStateService {
    private final TbEventStore store;
    private final SessionState sessionState;
    private final List<GroupStateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Trialbound-EventStore");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private String groupKey;
    private Map<String, TbEventRecord> events = new HashMap<>();
    private Map<Integer, TbEventRecord> unlockedByItem = new HashMap<>();
    private Map<String, Integer> balances = new HashMap<>();
    private int pooledGrit;
    private volatile boolean ready;

    @Inject
    public GroupStateService(TbEventStore store, SessionState sessionState) {
        this.store = store;
        this.sessionState = sessionState;
    }

    /** Group key: hash of the party passphrase, or "solo" when none is set. */
    public static String deriveGroupKey(String partyPassphrase) {
        String phrase = partyPassphrase == null ? "" : partyPassphrase.trim();
        if (phrase.isEmpty()) {
            return "solo";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(phrase.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public void initialize(String newGroupKey) {
        synchronized (lock) {
            groupKey = newGroupKey;
            events = new HashMap<>();
            for (TbEventRecord event : store.load(newGroupKey)) {
                putWinner(event);
            }
            rebuild();
            ready = true;
            log.info("Group state '{}' loaded: {} events, {} unlocks, pooled grit {}",
                    newGroupKey, events.size(), unlockedByItem.size(), pooledGrit);
        }
    }

    public void close() {
        synchronized (lock) {
            ready = false;
            groupKey = null;
            events = new HashMap<>();
            rebuild();
        }
    }

    public boolean isReady() {
        return ready;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void addListener(GroupStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(GroupStateListener listener) {
        listeners.remove(listener);
    }

    // --- local intents ---

    /** Records a DROP unlock; no-op if the item is already unlocked. */
    public void unlockDrop(int itemId, String itemName) {
        if (!ready || isUnlocked(itemId)) {
            return;
        }
        apply(Collections.singletonList(
                TbEventRecord.unlockDrop(itemId, itemName, currentPlayer(), System.currentTimeMillis())), false);
    }

    /** Spends pooled grit to unlock an item. */
    public PurchaseResult purchase(int itemId, String itemName, int cost) {
        if (!ready) {
            return PurchaseResult.NOT_READY;
        }
        int generation;
        synchronized (lock) {
            if (unlockedByItem.containsKey(itemId)) {
                return PurchaseResult.ALREADY_UNLOCKED;
            }
            if (pooledGrit < cost) {
                return PurchaseResult.INSUFFICIENT_GRIT;
            }
            generation = relockCount(itemId);
        }
        long now = System.currentTimeMillis();
        String player = currentPlayer();
        List<TbEventRecord> batch = new ArrayList<>(2);
        batch.add(TbEventRecord.unlockPurchase(itemId, itemName, player, cost, now, generation));
        batch.add(TbEventRecord.purchaseSpend(itemId, player, cost, now, generation));
        apply(batch, false);
        synchronized (lock) {
            return unlockedByItem.containsKey(itemId) ? PurchaseResult.SUCCESS : PurchaseResult.CONFLICT;
        }
    }

    /** How many times the item has been relocked; the purchase-id generation. */
    private int relockCount(int itemId) {
        int count = 0;
        for (TbEventRecord event : events.values()) {
            if (event.getKind() == TbEventKind.RELOCK && event.getItemId() != null && event.getItemId() == itemId) {
                count++;
            }
        }
        return count;
    }

    public void addTrialGrit(int itemId, int delta, String trialKey, int multiplierPercent) {
        if (!ready) {
            return;
        }
        apply(Collections.singletonList(TbEventRecord.trialGrit(itemId, currentPlayer(), delta, trialKey,
                multiplierPercent, System.currentTimeMillis())), false);
    }

    /** Manual grit adjustment (testing/admin). */
    public void addAdminGrit(int delta) {
        if (!ready) {
            return;
        }
        apply(Collections.singletonList(
                TbEventRecord.adminGrit(currentPlayer(), delta, System.currentTimeMillis())), false);
    }

    /** Admin re-lock tombstone; excludes earlier unlock events for the item. */
    public void relock(int itemId) {
        if (!ready) {
            return;
        }
        apply(Collections.singletonList(TbEventRecord.relock(itemId, currentPlayer(), System.currentTimeMillis())),
                false);
    }

    // --- transport merge ---

    /** Merges remote events; returns how many were newly applied. */
    public int mergeRemote(Collection<TbEventRecord> remote) {
        if (!ready) {
            return 0;
        }
        List<TbEventRecord> valid = remote.stream().filter(TbEventRecord::isValid).collect(Collectors.toList());
        return apply(valid, true).size();
    }

    // --- reads (snapshots) ---

    public Map<Integer, TbEventRecord> getUnlockedItems() {
        synchronized (lock) {
            return new HashMap<>(unlockedByItem);
        }
    }

    public boolean isUnlocked(int itemId) {
        synchronized (lock) {
            return unlockedByItem.containsKey(itemId);
        }
    }

    public int getGritBalance(String player) {
        synchronized (lock) {
            return balances.getOrDefault(player, 0);
        }
    }

    public Map<String, Integer> getBalancesByPlayer() {
        synchronized (lock) {
            return new HashMap<>(balances);
        }
    }

    public int getPooledGrit() {
        synchronized (lock) {
            return pooledGrit;
        }
    }

    public List<TbEventRecord> getRecentGritEvents(int limit) {
        synchronized (lock) {
            return events.values().stream()
                    .filter(e -> e.getKind() == TbEventKind.GRIT)
                    .sorted(Comparator.comparingLong(TbEventRecord::getCreatedOn).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }

    public int getGritEarnedForTrial(String trialKey) {
        synchronized (lock) {
            return events.values().stream()
                    .filter(e -> e.getKind() == TbEventKind.GRIT && trialKey.equals(e.getTrialKey())
                            && e.getDelta() != null && e.getDelta() > 0)
                    .mapToInt(TbEventRecord::getDelta)
                    .sum();
        }
    }

    /** Snapshot of every event, for transport reconciliation. */
    public List<TbEventRecord> getAllEvents() {
        synchronized (lock) {
            return new ArrayList<>(events.values());
        }
    }

    // --- internals ---

    private String currentPlayer() {
        String name = sessionState.getCurrentCharacter();
        return name.isEmpty() ? "unknown" : name;
    }

    private List<TbEventRecord> apply(List<TbEventRecord> batch, boolean remoteOrigin) {
        List<TbEventRecord> applied;
        List<TbEventRecord> addedUnlocks;
        List<Integer> removedUnlocks;
        String key;
        synchronized (lock) {
            key = groupKey;
            applied = new ArrayList<>();
            for (TbEventRecord event : batch) {
                if (putWinner(event)) {
                    applied.add(event);
                }
            }
            if (applied.isEmpty()) {
                return applied;
            }
            Map<Integer, TbEventRecord> before = unlockedByItem;
            rebuild();
            addedUnlocks = new ArrayList<>();
            for (Map.Entry<Integer, TbEventRecord> entry : unlockedByItem.entrySet()) {
                if (!before.containsKey(entry.getKey())) {
                    addedUnlocks.add(entry.getValue());
                }
            }
            removedUnlocks = new ArrayList<>();
            for (Integer itemId : before.keySet()) {
                if (!unlockedByItem.containsKey(itemId)) {
                    removedUnlocks.add(itemId);
                }
            }
        }
        if (key != null) {
            List<TbEventRecord> toPersist = applied;
            writer.execute(() -> store.append(key, toPersist));
        }
        boolean gritChanged = applied.stream().anyMatch(e -> e.getKind() == TbEventKind.GRIT);
        for (GroupStateListener listener : listeners) {
            listener.onEventsApplied(applied, remoteOrigin);
            if (!addedUnlocks.isEmpty()) {
                listener.onUnlocksAdded(addedUnlocks);
            }
            if (!removedUnlocks.isEmpty()) {
                listener.onUnlocksRemoved(removedUnlocks);
            }
            if (gritChanged) {
                listener.onGritChanged();
            }
        }
        return applied;
    }

    /** Inserts an event, resolving id collisions deterministically. Returns true if state changed. */
    private boolean putWinner(TbEventRecord event) {
        TbEventRecord existing = events.get(event.getId());
        if (existing == null) {
            events.put(event.getId(), event);
            return true;
        }
        if (existing.equals(event)) {
            return false;
        }
        if (TbEventRecord.WINNER_ORDER.compare(event, existing) < 0) {
            events.put(event.getId(), event);
            return true;
        }
        return false;
    }

    private void rebuild() {
        Map<Integer, Long> relockAt = new HashMap<>();
        for (TbEventRecord event : events.values()) {
            if (event.getKind() == TbEventKind.RELOCK) {
                relockAt.merge(event.getItemId(), event.getCreatedOn(), Math::max);
            }
        }

        Map<Integer, TbEventRecord> unlocks = new HashMap<>();
        for (TbEventRecord event : events.values()) {
            if (event.getKind() != TbEventKind.UNLOCK) {
                continue;
            }
            // Strictly earlier only: a re-unlock landing on the exact relock
            // millisecond (relock then immediate re-purchase) must survive.
            Long relock = relockAt.get(event.getItemId());
            if (relock != null && event.getCreatedOn() < relock) {
                continue; // tombstoned
            }
            unlocks.merge(event.getItemId(), event,
                    (a, b) -> TbEventRecord.WINNER_ORDER.compare(a, b) <= 0 ? a : b);
        }

        Map<String, Integer> newBalances = new HashMap<>();
        int pooled = 0;
        for (TbEventRecord event : events.values()) {
            if (event.getKind() != TbEventKind.GRIT || event.getDelta() == null) {
                continue;
            }
            String player = event.getPlayer() == null ? "unknown" : event.getPlayer();
            newBalances.merge(player, event.getDelta(), Integer::sum);
            pooled += event.getDelta();
        }

        unlockedByItem = unlocks;
        balances = newBalances;
        pooledGrit = pooled;
    }
}
