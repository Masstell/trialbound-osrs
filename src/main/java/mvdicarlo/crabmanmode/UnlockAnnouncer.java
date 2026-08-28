package mvdicarlo.crabmanmode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.store.TbEventRecord;
import mvdicarlo.crabmanmode.store.UnlockSource;
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
    private final TrialboundChat chat;

    private final Map<Integer, Integer> iconIdByItem = new ConcurrentHashMap<>();

    @Inject
    public UnlockAnnouncer(ItemManager itemManager, ChatIconManager chatIconManager, TrialboundChat chat) {
        this.itemManager = itemManager;
        this.chatIconManager = chatIconManager;
        this.chat = chat;
    }

    public void announce(TbEventRecord unlock) {
        int itemId = unlock.getItemId();
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> {
            int iconId = iconIdByItem.computeIfAbsent(itemId, k -> chatIconManager
                    .registerChatIcon(ImageUtil.resizeImage(image, ICON_WIDTH, ICON_HEIGHT)));
            chat.send(chatIconManager.chatIconIndex(iconId), message(unlock));
        });
    }

    public void announceRelock(int itemId, String itemName) {
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> {
            int iconId = iconIdByItem.computeIfAbsent(itemId, k -> chatIconManager
                    .registerChatIcon(ImageUtil.resizeImage(image, ICON_WIDTH, ICON_HEIGHT)));
            chat.send(chatIconManager.chatIconIndex(iconId), "Re-locked: " + itemName + ".");
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
