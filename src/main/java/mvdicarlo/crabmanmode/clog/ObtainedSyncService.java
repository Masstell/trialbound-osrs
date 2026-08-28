package mvdicarlo.crabmanmode.clog;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.CrabmanModePlugin;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.events.ObtainedSetChanged;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks which collection log items the player has personally obtained.
 *
 * The identity of obtained items can only be read while the collection log
 * interface is open: when it finishes building (script 7797) we auto-activate
 * its search (RuneProfile pattern), which makes the server transmit every
 * obtained entry via script 4100. The committed set persists per character
 * (RS-profile config) and is kept current between syncs by tracked drops;
 * drift is detected against the COLLECTION_COUNT varp.
 */
@Slf4j
@Singleton
public class ObtainedSyncService {
    public enum SyncState {
        NOT_SYNCED, SYNCING, SYNCED, STALE
    }

    private static final String KEY_OBTAINED = "obtainedClogItems";
    private static final String KEY_COUNT = "obtainedClogCount";
    private static final String KEY_SYNC_TIME = "lastClogSyncTime";

    /** Ticks of transmit silence after which the staged sync is committed. */
    private static final int COMMIT_QUIET_TICKS = 2;
    /** Ticks after which a sync that produced no entries is abandoned. */
    private static final int SYNC_TIMEOUT_TICKS = 50;

    private final Client client;
    private final ConfigManager configManager;
    private final EventBus eventBus;
    private final SessionState sessionState;
    private final CrabmanModeConfig config;
    private final TrialboundChat chat;

    private final Set<Integer> obtained = ConcurrentHashMap.newKeySet();
    private final Set<Integer> staging = new LinkedHashSet<>();

    private volatile SyncState state = SyncState.NOT_SYNCED;
    private boolean syncInProgress;
    private int syncStartTick;
    private int lastEntryTick;
    private int expectedCount;
    private String loadedProfileKey;
    private boolean nudgedSync;
    private boolean nudgedSetting;

    @Inject
    public ObtainedSyncService(Client client, ConfigManager configManager, EventBus eventBus,
            SessionState sessionState, CrabmanModeConfig config, TrialboundChat chat) {
        this.client = client;
        this.configManager = configManager;
        this.eventBus = eventBus;
        this.sessionState = sessionState;
        this.config = config;
        this.chat = chat;
    }

    public SyncState getState() {
        return state;
    }

    public boolean isObtained(int itemId) {
        return obtained.contains(itemId);
    }

    public Set<Integer> getObtainedItems() {
        return Collections.unmodifiableSet(obtained);
    }

    /** Records a drop detected by Trialbound itself, keeping drift tracking quiet. */
    public void markObtainedLocally(int itemId) {
        if (obtained.add(itemId)) {
            expectedCount++;
            persist();
            eventBus.post(new ObtainedSetChanged());
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (!sessionState.isActive()) {
            return;
        }

        String profileKey = configManager.getRSProfileKey();
        if (profileKey != null && !profileKey.equals(loadedProfileKey)) {
            loadPersisted(profileKey);
        }

        if (syncInProgress) {
            int now = client.getTickCount();
            if (!staging.isEmpty() && now - lastEntryTick >= COMMIT_QUIET_TICKS) {
                commit();
            } else if (staging.isEmpty() && now - syncStartTick > SYNC_TIMEOUT_TICKS) {
                log.debug("Collection log sync produced no entries; abandoning");
                syncInProgress = false;
                if (state == SyncState.SYNCING) {
                    state = loadedProfileKey != null && expectedCount > 0 ? SyncState.SYNCED : SyncState.NOT_SYNCED;
                }
            }
        }

        maybeNudge();
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() != ClogCacheIds.SCRIPT_CLOG_SETUP || !sessionState.isActive()) {
            return;
        }
        if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1) {
            // Someone else's log via the POH adventure log - never read it.
            if (syncInProgress) {
                syncInProgress = false;
                staging.clear();
            }
            return;
        }
        if (syncInProgress) {
            return; // our own search/reset re-fires the setup script
        }
        syncInProgress = true;
        state = SyncState.SYNCING;
        staging.clear();
        syncStartTick = lastEntryTick = client.getTickCount();
        client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
        client.runScript(ClogCacheIds.SCRIPT_CLOG_RESET);
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() != ClogCacheIds.SCRIPT_CLOG_TRANSMIT || !syncInProgress) {
            return;
        }
        Object[] args = event.getScriptEvent().getArguments();
        int itemId = (int) args[1];
        int quantity = (int) args[2];
        if (quantity > 0) {
            staging.add(itemId);
        }
        lastEntryTick = client.getTickCount();
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (event.getVarbitId() == VarbitID.OPTION_COLLECTION_NEW_ITEM) {
            nudgedSetting = false; // re-nudge if it gets turned off again
            return;
        }
        if (event.getVarpId() == VarPlayerID.COLLECTION_COUNT && event.getVarbitId() == -1
                && state == SyncState.SYNCED && event.getValue() > expectedCount) {
            state = SyncState.STALE;
            if (config.nudgeClogSync() && sessionState.isActive()) {
                chat.send("Your collection log changed outside Trialbound tracking - open your collection log to re-sync.");
            }
        }
    }

    private void commit() {
        obtained.clear();
        obtained.addAll(staging);
        staging.clear();
        syncInProgress = false;
        expectedCount = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
        state = SyncState.SYNCED;
        persist();
        eventBus.post(new ObtainedSetChanged());
        chat.send("Trialbound synced your collection log: " + obtained.size() + " obtained items.");
        log.info("Committed collection log sync: {} items, expected count {}", obtained.size(), expectedCount);
    }

    private void loadPersisted(String profileKey) {
        loadedProfileKey = profileKey;
        obtained.clear();
        expectedCount = 0;
        state = SyncState.NOT_SYNCED;
        String csv = configManager.getRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_OBTAINED);
        if (csv == null || csv.isEmpty()) {
            eventBus.post(new ObtainedSetChanged());
            return;
        }
        try {
            for (String part : csv.split(",")) {
                obtained.add(Integer.parseInt(part.trim()));
            }
            String count = configManager.getRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_COUNT);
            expectedCount = count != null ? Integer.parseInt(count) : obtained.size();
            state = SyncState.SYNCED;
            log.debug("Loaded {} obtained clog items for profile {}", obtained.size(), profileKey);
        } catch (NumberFormatException e) {
            log.warn("Corrupt persisted obtained set; forcing re-sync", e);
            obtained.clear();
            expectedCount = 0;
            state = SyncState.NOT_SYNCED;
        }
        eventBus.post(new ObtainedSetChanged());
    }

    private void persist() {
        if (loadedProfileKey == null) {
            return;
        }
        String csv = obtained.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        configManager.setRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_OBTAINED, csv);
        configManager.setRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_COUNT, expectedCount);
        configManager.setRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_SYNC_TIME, Instant.now().toString());
    }

    private void maybeNudge() {
        if (state == SyncState.NOT_SYNCED && !nudgedSync && config.nudgeClogSync() && loadedProfileKey != null) {
            nudgedSync = true;
            chat.send("Trialbound: open your Collection Log once so your obtained items can be synced.");
        }
        if (!nudgedSetting && config.nudgeClogSetting()
                && client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) == 0) {
            nudgedSetting = true;
            chat.send("Trialbound: enable the game setting 'Collection log - New addition notification' for the best drop detection.");
        }
    }
}
