package mvdicarlo.crabmanmode.trial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.clog.ClogText;
import mvdicarlo.crabmanmode.events.ClogDataLoaded;
import mvdicarlo.crabmanmode.events.TrialsChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Deterministic trial selection: every client derives the same five trials
 * (daily wildcard 3x, one weekly boss per tier 2x, monthly raid 1.5x) from the
 * UTC calendar date and the shared tier file, with zero coordination.
 */
@Slf4j
@Singleton
public class TrialService {
    private final BossTierRegistry registry;
    private final EventBus eventBus;
    private final SessionState sessionState;
    private final TrialboundChat chat;
    private final mvdicarlo.crabmanmode.CrabmanModeConfig config;

    private volatile List<TrialSlot> activeTrials = Collections.emptyList();
    private long computedEpochDay = Long.MIN_VALUE;

    @Inject
    public TrialService(BossTierRegistry registry, EventBus eventBus, SessionState sessionState, TrialboundChat chat,
            mvdicarlo.crabmanmode.CrabmanModeConfig config) {
        this.registry = registry;
        this.eventBus = eventBus;
        this.sessionState = sessionState;
        this.chat = chat;
        this.config = config;
    }

    /** Re-evaluates trials now (config override changed). */
    public void refresh() {
        computedEpochDay = Long.MIN_VALUE;
        recompute(false);
    }

    public List<TrialSlot> getActiveTrials() {
        return activeTrials;
    }

    /** Highest multiplier among active trials for this page; empty = off-trial. */
    public OptionalInt getMultiplierPercent(String pageName) {
        return getSlotForPage(pageName).map(s -> OptionalInt.of(s.getMultiplierPercent())).orElse(OptionalInt.empty());
    }

    /** The winning (highest-multiplier) slot containing this page. */
    public Optional<TrialSlot> getSlotForPage(String pageName) {
        String normalized = ClogText.normalize(pageName);
        TrialSlot best = null;
        for (TrialSlot slot : activeTrials) {
            if (ClogText.normalize(slot.getPageName()).equals(normalized)
                    && (best == null || slot.getMultiplierPercent() > best.getMultiplierPercent())) {
                best = slot;
            }
        }
        return Optional.ofNullable(best);
    }

    @Subscribe
    public void onClogDataLoaded(ClogDataLoaded event) {
        recompute(true);
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (LocalDate.now(ZoneOffset.UTC).toEpochDay() != computedEpochDay) {
            boolean rollover = computedEpochDay != Long.MIN_VALUE;
            recompute(rollover);
        }
    }

    private void recompute(boolean announce) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        computedEpochDay = today.toEpochDay();
        List<TrialSlot> slots = computeTrials(today, registry);

        String override = config.trialDailyOverride().trim();
        if (!override.isEmpty()) {
            List<TrialSlot> adjusted = new java.util.ArrayList<>(slots.size());
            for (TrialSlot slot : slots) {
                adjusted.add(slot.getType() == TrialType.DAILY
                        ? new TrialSlot(TrialType.DAILY, override, slot.getPeriodKey(), slot.getPeriodEndUtc())
                        : slot);
            }
            slots = Collections.unmodifiableList(adjusted);
        }

        if (slots.equals(activeTrials)) {
            return;
        }
        activeTrials = slots;
        eventBus.post(new TrialsChanged(slots));
        if (announce && sessionState.isActive() && !slots.isEmpty()) {
            chat.send("Trialbound trials: " + summarize(slots));
        }
        log.info("Active trials: {}", summarize(slots));
    }

    private static String summarize(List<TrialSlot> slots) {
        StringBuilder sb = new StringBuilder();
        for (TrialSlot slot : slots) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(slot.getType().getDisplayName()).append(": ").append(slot.getPageName())
                    .append(" (").append(slot.getType().getMultiplierLabel()).append(")");
        }
        return sb.toString();
    }

    // --- pure selection logic (unit-tested) ---

    /** Builds the five slots for a UTC date; slots with an empty pool are skipped. */
    public static List<TrialSlot> computeTrials(LocalDate date, BossTierRegistry registry) {
        String daily = dailyKey(date);
        String weekly = weeklyKey(date);
        String monthly = monthlyKey(date);

        List<String> dailyPool = new ArrayList<>(new TreeSet<>(union(
                registry.getPages(TrialTier.EASY), registry.getPages(TrialTier.MEDIUM),
                registry.getPages(TrialTier.HARD), registry.getPages(TrialTier.RAID))));

        List<TrialSlot> slots = new ArrayList<>(5);
        addSlot(slots, TrialType.DAILY, daily, dailyEnd(date), dailyPool);
        addSlot(slots, TrialType.WEEKLY_EASY, weekly, weeklyEnd(date), registry.getPages(TrialTier.EASY));
        addSlot(slots, TrialType.WEEKLY_MEDIUM, weekly, weeklyEnd(date), registry.getPages(TrialTier.MEDIUM));
        addSlot(slots, TrialType.WEEKLY_HARD, weekly, weeklyEnd(date), registry.getPages(TrialTier.HARD));
        addSlot(slots, TrialType.MONTHLY, monthly, monthlyEnd(date), registry.getPages(TrialTier.RAID));
        return Collections.unmodifiableList(slots);
    }

    private static void addSlot(List<TrialSlot> slots, TrialType type, String periodKey, Instant end,
            List<String> pool) {
        if (pool.isEmpty()) {
            return;
        }
        String page = pool.get(pickIndex(periodKey, type.getSlotSalt(), pool.size()));
        slots.add(new TrialSlot(type, page, periodKey, end));
    }

    @SafeVarargs
    private static List<String> union(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    public static String dailyKey(LocalDate date) {
        return date.toString(); // ISO yyyy-MM-dd
    }

    /** ISO week key using the WEEK-BASED year so Dec/Jan boundaries agree everywhere. */
    public static String weeklyKey(LocalDate date) {
        return String.format("%d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static String monthlyKey(LocalDate date) {
        return String.format("%04d-%02d", date.getYear(), date.getMonthValue());
    }

    public static Instant dailyEnd(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant weeklyEnd(LocalDate date) {
        return date.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant monthlyEnd(LocalDate date) {
        return date.with(TemporalAdjusters.firstDayOfNextMonth()).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Deterministic pool index: first 8 bytes of SHA-256(periodKey:salt) as a
     * big-endian long, floorMod pool size. Identical on every JVM.
     */
    public static int pickIndex(String periodKey, String slotSalt, int poolSize) {
        byte[] digest = sha256((periodKey + ":" + slotSalt).getBytes(StandardCharsets.UTF_8));
        long seed = 0;
        for (int i = 0; i < 8; i++) {
            seed = (seed << 8) | (digest[i] & 0xFF);
        }
        return (int) Math.floorMod(seed, poolSize);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
