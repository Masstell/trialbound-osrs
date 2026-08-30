package mvdicarlo.trialbound;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.trialbound.store.TbEventRecord;
import mvdicarlo.trialbound.store.UnlockSource;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
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

    /** Matches the Grit-gold used elsewhere in the panel. */
    private static final Color GOLD = new Color(0xff, 0xc8, 0x3c);
    private static final Color GREEN = ColorScheme.PROGRESS_COMPLETE_COLOR;

    private final Client client;
    private final ItemManager itemManager;
    private final ChatIconManager chatIconManager;
    private final ClientThread clientThread;
    private final TrialboundChat chat;

    private final Map<Integer, Integer> iconIdByItem = new ConcurrentHashMap<>();
    /** Registered once, lazily, on first announcement; -1 until then. Client thread only. */
    private int badgeIconId = -1;

    @Inject
    public UnlockAnnouncer(Client client, ItemManager itemManager, ChatIconManager chatIconManager,
            ClientThread clientThread, TrialboundChat chat) {
        this.client = client;
        this.itemManager = itemManager;
        this.chatIconManager = chatIconManager;
        this.clientThread = clientThread;
        this.chat = chat;
    }

    public void announce(TbEventRecord unlock) {
        String middle;
        String suffix;
        if (unlock.getSource() == UnlockSource.PURCHASE) {
            middle = " has purchased an unlock: ";
            suffix = unlock.getItemName() + " (" + unlock.getCost() + " Grit).";
        } else {
            middle = " has unlocked a new item: ";
            suffix = unlock.getItemName() + ".";
        }
        sendUnlock(unlock.getPlayer(), middle, unlock.getItemId(), suffix);
    }

    public void announceRelock(int itemId, String itemName) {
        sendWithItemIcon(itemId, "Re-locked: ", itemName + ".");
    }

    /**
     * The Trialbound badge next to the gold username, then the rest of the
     * message in green with the item's icon inlined before its name. Like
     * {@link #sendWithItemIcon}, the item icon is best-effort: if it hasn't
     * loaded within 3 s the message goes out without it.
     */
    private void sendUnlock(String player, String middleText, int itemId, String suffix) {
        AtomicBoolean sent = new AtomicBoolean();
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.onLoaded(() -> {
            int itemIconId = iconIdByItem.computeIfAbsent(itemId, k -> chatIconManager
                    .registerChatIcon(ImageUtil.resizeImage(image, ICON_WIDTH, ICON_HEIGHT)));
            clientThread.invokeLater(() -> {
                if (sent.compareAndSet(false, true)) {
                    chat.send(buildUnlockMessage(player, middleText,
                            chatIconManager.chatIconIndex(itemIconId), suffix));
                }
            });
        });
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            if (sent.compareAndSet(false, true)) {
                clientThread.invokeLater(() -> chat.send(buildUnlockMessage(player, middleText, -1, suffix)));
            }
        });
    }

    private ChatMessageBuilder buildUnlockMessage(String player, String middleText, int itemIconIndex,
            String suffix) {
        ChatMessageBuilder builder = new ChatMessageBuilder();
        if (badgeIconId == -1) {
            badgeIconId = chatIconManager.registerChatIcon(TrialboundPlugin.chatBadgeImage());
        }
        int badgeIndex = chatIconManager.chatIconIndex(badgeIconId);
        if (badgeIndex >= 0) {
            // ChatIconManager doesn't expose sprite offsets itself; reach into
            // the mod icon it registered to nudge it vertically centered,
            // same as the always-on name badge in TrialboundPlugin.
            IndexedSprite[] modIcons = client.getModIcons();
            if (modIcons != null && badgeIndex < modIcons.length && modIcons[badgeIndex] != null) {
                modIcons[badgeIndex].setOffsetY(TrialboundPlugin.BADGE_OFFSET_Y);
            }
            builder.img(badgeIndex).append(" ");
        }
        builder.append(GOLD, player).append(GREEN, middleText);
        if (itemIconIndex >= 0) {
            builder.img(itemIconIndex);
        }
        return builder.append(GREEN, suffix);
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
