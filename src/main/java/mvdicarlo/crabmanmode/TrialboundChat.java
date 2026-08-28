package mvdicarlo.crabmanmode;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/** Console chat output shared by Trialbound services. */
@Singleton
public class TrialboundChat {
    private final ChatMessageManager chatMessageManager;

    @Inject
    public TrialboundChat(ChatMessageManager chatMessageManager) {
        this.chatMessageManager = chatMessageManager;
    }

    public void send(String message) {
        send(-1, message);
    }

    /** Message prefixed with a chat mod-icon (index from ChatIconManager), or none when < 0. */
    public void send(int imgIndex, String message) {
        ChatMessageBuilder builder = new ChatMessageBuilder();
        if (imgIndex >= 0) {
            builder.img(imgIndex);
        }
        builder.append(ChatColorType.HIGHLIGHT).append(message);
        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(builder.build())
                .build());
    }
}
