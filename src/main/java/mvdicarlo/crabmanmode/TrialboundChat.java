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
        chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(new ChatMessageBuilder()
                        .append(ChatColorType.HIGHLIGHT)
                        .append(message)
                        .build())
                .build());
    }
}
