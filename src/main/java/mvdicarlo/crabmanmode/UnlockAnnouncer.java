package mvdicarlo.crabmanmode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.store.TbEventRecord;
import mvdicarlo.crabmanmode.store.UnlockSource;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Unlock chat announcements with the item's sprite inlined as a chat icon.
 * Item sprites load asynchronously, so the message is sent from the image
 * callback (immediately when cached); registered icons are reused per item.
 */
@Singleton
public class UnlockAnnouncer {
    private static final int ICON_WIDTH = 18;
    private static final int ICON_HEIGHT = 16;

    private final ItemManager itemManager;
    private final ChatIconManager chatIconManager;
    private final ClientThread clientThread;
    private final TrialboundChat chat;

    private final Map<Integer, Integer> iconIdByItem = new ConcurrentHashMap<>();

    @Inject
    public UnlockAnnouncer(ItemManager itemManager, ChatIconManager chatIconManager, ClientThread clientThread,
            TrialboundChat chat) {
        this.itemManager = itemManager;
        this.chatIconManager = chatIconManager;
        this.clientThread = clientThread;
        this.chat = chat;
    }

    public void announce(TbEventRecord unlock) {
        sendWithItemIcon(unlock.getItemId(), message(unlock));
    }

    public void announceRelock(int itemId, String itemName) {
        sendWithItemIcon(itemId, "Re-locked: " + itemName + ".");
    }

    /**
     * The icon index is only assigned by ChatIconManager's refresh, which it
     * queues on the client thread - so the send is queued behind it, or the
     * first message per item would always come out iconless. The message must
     * never depend on the sprite though: if the image has not loaded within
     * 3 s the announcement goes out without an icon.
     */
    private void sendWithItemIcon(int itemId, String message) {
        AtomicBoolean sent = new AtomicBoolean();
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> {
            int iconId = iconIdByItem.computeIfAbsent(itemId, k -> chatIconManager
                    .registerChatIcon(ImageUtil.resizeImage(image, ICON_WIDTH, ICON_HEIGHT)));
            clientThread.invokeLater(() -> {
                if (sent.compareAndSet(false, true)) {
                    chat.send(chatIconManager.chatIconIndex(iconId), message);
                }
            });
        });
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            if (sent.compareAndSet(false, true)) {
                chat.send(message);
            }
        });
    }

    private static String message(TbEventRecord unlock) {
        if (unlock.getSource() == UnlockSource.PURCHASE) {
            return unlock.getPlayer() + " has purchased an unlock: " + unlock.getItemName()
                    + " (" + unlock.getCost() + " Grit).";
        }
        return unlock.getPlayer() + " has unlocked a new item: " + unlock.getItemName() + ".";
    }
}
