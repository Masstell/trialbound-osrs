package mvdicarlo.crabmanmode;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.store.GroupStateListener;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * On login, reports the unlocks recorded since this character last saw them -
 * teammate unlocks merged while away, panel purchases made while logged out,
 * and unlocks announced (and skipped) while a different character was on.
 *
 * A per-RS-profile watermark tracks the newest unlock the character has seen;
 * live announcements move it forward so relogging never repeats them. The
 * very first login only sets the baseline instead of replaying all history.
 */
@Slf4j
@Singleton
public class LoginUnlockSummary implements GroupStateListener {
    private static final String KEY_LAST_SEEN = "lastSeenUnlockMillis";
    private static final int MAX_NAMED = 6;

    private final Client client;
    private final ConfigManager configManager;
    private final SessionState sessionState;
    private final GroupStateService groupState;
    private final CrabmanModeConfig config;
    private final TrialboundChat chat;

    private boolean summarized;

    @Inject
    public LoginUnlockSummary(Client client, ConfigManager configManager, SessionState sessionState,
            GroupStateService groupState, CrabmanModeConfig config, TrialboundChat chat) {
        this.client = client;
        this.configManager = configManager;
        this.sessionState = sessionState;
        this.groupState = groupState;
        this.config = config;
        this.chat = chat;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() != GameState.LOGGED_IN) {
            summarized = false;
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (summarized || client.getGameState() != GameState.LOGGED_IN
                || !sessionState.isActive() || !groupState.isReady()
                || configManager.getRSProfileKey() == null) {
            return;
        }
        summarized = true;
        long lastSeen = readWatermark();
        long now = System.currentTimeMillis();
        writeWatermark(now);
        if (lastSeen <= 0 || !config.loginSummary()) {
            return; // first login on this character: baseline only
        }
        List<TbEventRecord> missed = groupState.getUnlockedItems().values().stream()
                .filter(unlock -> unlock.getCreatedOn() > lastSeen)
                .sorted(Comparator.comparingLong(TbEventRecord::getCreatedOn))
                .collect(Collectors.toList());
        if (missed.isEmpty()) {
            return;
        }
        if (missed.size() == 1) {
            TbEventRecord unlock = missed.get(0);
            chat.send("Trialbound: while you were away, " + unlock.getPlayer()
                    + " unlocked " + unlock.getItemName() + ".");
            return;
        }
        String names = missed.stream()
                .limit(MAX_NAMED)
                .map(unlock -> unlock.getItemName() + " (" + unlock.getPlayer() + ")")
                .collect(Collectors.joining(", "));
        String more = missed.size() > MAX_NAMED ? " +" + (missed.size() - MAX_NAMED) + " more" : "";
        chat.send("Trialbound: " + missed.size() + " items were unlocked while you were away: "
                + names + more + ".");
    }

    /**
     * Live unlocks (drops, purchases, merges announced in chat) move the
     * watermark so a relog doesn't replay them in the summary.
     */
    @Override
    public void onUnlocksAdded(List<TbEventRecord> unlocks) {
        if (client.getGameState() == GameState.LOGGED_IN && sessionState.isActive()
                && configManager.getRSProfileKey() != null) {
            writeWatermark(System.currentTimeMillis());
        }
    }

    private long readWatermark() {
        String value = configManager.getRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_LAST_SEEN);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Corrupt login-summary watermark '{}'", value);
            return 0;
        }
    }

    private void writeWatermark(long millis) {
        configManager.setRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_LAST_SEEN, millis);
    }
}
