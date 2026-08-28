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
        String prefix;
        String suffix;
        if (unlock.getSource() == UnlockSource.PURCHASE) {
            prefix = unlock.getPlayer() + " has purchased an unlock: ";
            suffix = unlock.getItemName() + " (" + unlock.getCost() + " Grit).";
        } else {
            prefix = unlock.getPlayer() + " has unlocked a new item: ";
            suffix = unlock.getItemName() + ".";
        }
        sendWithItemIcon(unlock.getItemId(), prefix, suffix);
    }

    public void announceRelock(int itemId, String itemName) {
        sendWithItemIcon(itemId, "Re-locked: ", itemName + ".");
    }

    /**
     * The icon renders inline, just before the item name. Its index is only
     * assigned by ChatIconManager's refresh, which it queues on the client
     * thread - so the send is queued behind it, or the first message per item
     * would come out iconless. The message must never depend on the sprite
     * though: if the image has not loaded within 3 s it goes out without one.
     */
    private void sendWithItemIcon(int itemId, String prefix, String suffix) {
        AtomicBoolean sent = new AtomicBoolean();
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> {
            int iconId = iconIdByItem.computeIfAbsent(itemId, k -> chatIconManager
                    .registerChatIcon(ImageUtil.resizeImage(image, ICON_WIDTH, ICON_HEIGHT)));
            clientThread.invokeLater(() -> {
                if (sent.compareAndSet(false, true)) {
                    chat.send(prefix, chatIconManager.chatIconIndex(iconId), suffix);
                }
            });
        });
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            if (sent.compareAndSet(false, true)) {
                chat.send(prefix + suffix);
            }
        });
    }
}
