package mvdicarlo.crabmanmode.clog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.loot.DropAttributionService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Detects "new collection log item" notifications via both game paths - the
 * chat message and the on-screen popup - and hands the item name to the drop
 * attribution service for correlation with loot events.
 */
@Slf4j
@Singleton
public class ClogUnlockDetector {
    private static final Pattern NEW_CLOG_ITEM = Pattern.compile("New item added to your collection log: (.*)");
    private static final String POPUP_TITLE = "Collection log";
    private static final String POPUP_PREFIX = "New item:";

    private final Client client;
    private final SessionState sessionState;
    private final DropAttributionService attribution;

    private boolean popupPending;

    @Inject
    public ClogUnlockDetector(Client client, SessionState sessionState, DropAttributionService attribution) {
        this.client = client;
        this.sessionState = sessionState;
        this.attribution = attribution;
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE || !sessionState.isActive()) {
            return;
        }
        Matcher matcher = NEW_CLOG_ITEM.matcher(Text.removeTags(event.getMessage()));
        if (matcher.matches()) {
            attribution.onClogNotification(matcher.group(1).trim());
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() == ScriptID.NOTIFICATION_START) {
            popupPending = true;
            return;
        }
        if (event.getScriptId() != ScriptID.NOTIFICATION_DELAY || !popupPending) {
            return;
        }
        popupPending = false;
        if (!sessionState.isActive()) {
            return;
        }
        String title = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
        String text = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);
        if (title == null || !title.equalsIgnoreCase(POPUP_TITLE) || text == null) {
            return;
        }
        String clean = Text.removeTags(text);
        if (clean.startsWith(POPUP_PREFIX)) {
            attribution.onClogNotification(clean.substring(POPUP_PREFIX.length()).trim());
        }
    }
}
