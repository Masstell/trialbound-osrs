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
        queue(new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(message));
    }

    /** "prefix<icon>suffix" with the icon inline (index from ChatIconManager), or plain when < 0. */
    public void send(String prefix, int imgIndex, String suffix) {
        ChatMessageBuilder builder = new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append(prefix);
        if (imgIndex >= 0) {
            builder.img(imgIndex);
        }
        queue(builder.append(suffix));
    }

    private void queue(ChatMessageBuilder builder) {
        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(builder.build())
                .build());
    }
}
