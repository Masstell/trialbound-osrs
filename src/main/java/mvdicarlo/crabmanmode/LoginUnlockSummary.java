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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * On login, reports the unlocks recorded since this character last saw them -
 * teammate unlocks merged while away, panel purchases made while logged out,
 * and unlocks announced (and skipped) while a different character was on.
 *
 * A per-RS-profile watermark tracks the newest unlock the character has seen;
 * live announcements move it forward so relogging never repeats them. The
 * watermark always holds an unlock's own createdOn (a teammate's clock), never
 * this machine's clock - comparing the two would drop or repeat unlocks under
 * clock skew. The very first login only sets the baseline instead of
 * replaying all history.
 */
@Slf4j
@Singleton
public class LoginUnlockSummary implements GroupStateListener {
    private static final String KEY_LAST_SEEN = "lastSeenUnlockMillis";
    private static final int MAX_NAMED = 6;

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configManager;
    private final SessionState sessionState;
    private final GroupStateService groupState;
    private final CrabmanModeConfig config;
    private final TrialboundChat chat;

    private boolean summarized;

    @Inject
    public LoginUnlockSummary(Client client, ClientThread clientThread, ConfigManager configManager,
            SessionState sessionState, GroupStateService groupState, CrabmanModeConfig config, TrialboundChat chat) {
        this.client = client;
        this.clientThread = clientThread;
        this.configManager = configManager;
        this.sessionState = sessionState;
        this.groupState = groupState;
        this.config = config;
        this.chat = chat;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        // Only a real logout re-arms the summary: LOADING fires on every
        // region crossing and HOPPING on world hops, mid-session.
        if (event.getGameState() == GameState.LOGIN_SCREEN) {
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
        List<TbEventRecord> unlocks = groupState.getUnlockedItems().values().stream()
                .sorted(Comparator.comparingLong(TbEventRecord::getCreatedOn))
                .collect(Collectors.toList());
        long newestSeen = unlocks.isEmpty() ? 0 : unlocks.get(unlocks.size() - 1).getCreatedOn();
        // Positive floor: with zero unlocks the baseline must still be
        // written, or every login would stay a "first login".
        writeWatermarkIfNewer(Math.max(newestSeen, 1));
        if (lastSeen <= 0 || !config.loginSummary()) {
            return; // first login on this character: baseline only
        }
        List<TbEventRecord> missed = unlocks.stream()
                .filter(unlock -> unlock.getCreatedOn() > lastSeen)
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
        long newest = unlocks.stream().mapToLong(TbEventRecord::getCreatedOn).max().orElse(0);
        if (newest <= 0) {
            return;
        }
        // Advance only once the announcement had a chance to render: the
        // plugin defers announcements to the client thread and drops them
        // when the player is no longer logged in - mirror that check here,
        // so a merge racing a logout is reported on the next login instead
        // of being swallowed. If the announcement was dropped mid-session
        // (world hop), re-arm the summary to catch up when play resumes.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && sessionState.isActive()
                    && configManager.getRSProfileKey() != null) {
                writeWatermarkIfNewer(newest);
            } else {
                summarized = false;
            }
        });
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

    private void writeWatermarkIfNewer(long millis) {
        if (millis > readWatermark()) {
            configManager.setRSProfileConfiguration(CrabmanModePlugin.CONFIG_GROUP, KEY_LAST_SEEN, millis);
        }
    }
}
