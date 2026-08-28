/*
 * Original License
 * Copyright (c) 2019, CodePanter <https://github.com/codepanter>
 * Copyright (c) 2024, mvdicarlo <https://github.com/mvdicarlo>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package mvdicarlo.crabmanmode;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.google.inject.Provides;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.clog.ClogUnlockDetector;
import mvdicarlo.crabmanmode.clog.ObtainedSyncService;
import mvdicarlo.crabmanmode.store.GroupStateListener;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import mvdicarlo.crabmanmode.store.UnlockSource;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ChatPlayer;
import net.runelite.api.Client;
import net.runelite.api.Friend;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GameState;
import net.runelite.api.IconID;
import net.runelite.api.IndexedSprite;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MessageNode;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.NameableContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

@Slf4j
@PluginDescriptor(name = "Trialbound", description = "Collection-log-locked game mode: clog items are locked until obtained or purchased with Grit earned from rotating boss trials. Solo or group.", tags = {
        "overlay", "collection log", "clog", "grit", "trials", "bronzeman", "group" })
public class CrabmanModePlugin extends Plugin {
    public static final String CONFIG_GROUP = "crabmanmode";
    private static final String TB_UNLOCKS_STRING = "!tbunlocks";
    private static final String TB_RECENT_STRING = "!tbrecent";
    private static final String TB_CLOG_DEBUG_STRING = "!tbclog";
    private static final String TB_GRIT_STRING = "!grit";
    /** Testing backdoor - remove or gate before real group play. */
    private static final String TB_GRANT_STRING = "!tbgrant";

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private WorldService worldService;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ChatCommandManager chatCommandManager;

    @Inject
    private CrabmanModeConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private SessionState sessionState;

    @Inject
    private ClogDataService clogDataService;

    @Inject
    private ObtainedSyncService obtainedSyncService;

    @Inject
    private mvdicarlo.crabmanmode.loot.DropAttributionService dropAttributionService;

    @Inject
    private ClogUnlockDetector clogUnlockDetector;

    @Inject
    private mvdicarlo.crabmanmode.trial.TrialService trialService;

    @Inject
    private net.runelite.client.eventbus.EventBus eventBus;

    @Inject
    private CrabmanModeOverlay CrabmanModeOverlay;

    @Inject
    private GroupStateService groupState;

    @Inject
    private UnlockCoordinator unlockCoordinator;

    @Inject
    private UnlockAnnouncer unlockAnnouncer;

    @Inject
    private mvdicarlo.crabmanmode.grit.GritService gritService;

    @Inject
    private mvdicarlo.crabmanmode.sync.PartySyncService partySyncService;

    @Inject
    private mvdicarlo.crabmanmode.enforcement.GeEnforcementService geEnforcementService;

    @Inject
    private mvdicarlo.crabmanmode.enforcement.EquipEnforcementService equipEnforcementService;

    @Inject
    private mvdicarlo.crabmanmode.enforcement.TradeWarningService tradeWarningService;

    @Inject
    private mvdicarlo.crabmanmode.enforcement.TradeWarningOverlay tradeWarningOverlay;

    @Inject
    private mvdicarlo.crabmanmode.enforcement.LockedItemOverlay lockedItemOverlay;

    @Inject
    private mvdicarlo.crabmanmode.ui.ClogHighlightOverlay clogHighlightOverlay;

    @Inject
    private mvdicarlo.crabmanmode.ui.ClogMenuService clogMenuService;

    @Inject
    private mvdicarlo.crabmanmode.ui.GritToastOverlay gritToastOverlay;

    @Inject
    private mvdicarlo.crabmanmode.ui.TrialsOverlay trialsOverlay;

    @Getter
    private BufferedImage unlockImage = null;

    @Inject
    private ClientToolbar clientToolbar;

    private NavigationButton navButton;

    private static final String SCRIPT_EVENT_SET_CHATBOX_INPUT = "setChatboxInput";
    private List<String> namesBronzeman = new ArrayList<>();
    private String enabledCrabman = "";
    private int bronzemanIconOffset = -1; // offset for bronzeman icon
    private boolean onSeasonalWorld;

    private CrabmanModePanel panel;

    private final GroupStateListener groupStateListener = new GroupStateListener() {
        @Override
        public void onUnlocksAdded(List<TbEventRecord> unlocks) {
            clientThread.invokeLater(() -> {
                if (!isLoggedIntoCrabman()) {
                    return;
                }
                for (TbEventRecord unlock : unlocks) {
                    CrabmanModeOverlay.addItemUnlock(unlock.getItemId());
                    unlockAnnouncer.announce(unlock);
                }
            });
            refreshPanel(true, false);
        }

        @Override
        public void onUnlocksRemoved(List<Integer> itemIds) {
            clientThread.invokeLater(() -> {
                if (!isLoggedIntoCrabman()) {
                    return;
                }
                for (int itemId : itemIds) {
                    unlockAnnouncer.announceRelock(itemId, client.getItemDefinition(itemId).getName());
                }
            });
            refreshPanel(true, false);
        }

        @Override
        public void onGritChanged() {
            refreshPanel(false, true);
        }
    };

    /** Marshals panel refreshes to the Swing thread. */
    private void refreshPanel(boolean unlocks, boolean grit) {
        if (panel == null) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (unlocks) {
                panel.refreshUnlocks();
            }
            if (grit) {
                panel.refreshGrit();
                panel.refreshTrials();
            }
        });
    }

    @Subscribe
    public void onTrialsChanged(mvdicarlo.crabmanmode.events.TrialsChanged event) {
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(panel::refreshTrials);
        }
    }

    @Subscribe
    public void onClogDataLoaded(mvdicarlo.crabmanmode.events.ClogDataLoaded event) {
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(panel::refreshAll);
        }
    }

    @Provides
    CrabmanModeConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CrabmanModeConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        super.startUp();
        onSeasonalWorld = false;
        updateNamesBronzeman();
        updateAllowedCrabman();

        panel = injector.getInstance(CrabmanModePanel.class);
        final BufferedImage icon = ImageUtil.resizeImage(
                ImageUtil.loadImageResource(getClass(), "/trialbound_icon.png"), 16, 16);

        navButton = NavigationButton.builder()
                .tooltip("Trialbound")
                .icon(icon)
                .panel(panel)
                .priority(6)
                .build();

        clientToolbar.addNavigation(navButton);
        loadResources();
        groupState.addListener(groupStateListener);
        initializeGroupState();

        overlayManager.add(CrabmanModeOverlay);
        chatCommandManager.registerCommand(TB_UNLOCKS_STRING, this::OnUnlocksCountCommand);
        chatCommandManager.registerCommand(TB_RECENT_STRING, this::OnRecentUnlocksCommand);
        chatCommandManager.registerCommand(TB_CLOG_DEBUG_STRING, this::onClogDebugCommand);
        chatCommandManager.registerCommand(TB_GRIT_STRING, this::onGritCommand);
        chatCommandManager.registerCommand(TB_GRANT_STRING, this::onGrantCommand);

        eventBus.register(obtainedSyncService);
        eventBus.register(dropAttributionService);
        eventBus.register(clogUnlockDetector);
        eventBus.register(trialService);
        eventBus.register(unlockCoordinator);
        eventBus.register(gritService);
        eventBus.register(partySyncService);
        eventBus.register(geEnforcementService);
        eventBus.register(equipEnforcementService);
        eventBus.register(tradeWarningService);
        eventBus.register(clogMenuService);
        overlayManager.add(tradeWarningOverlay);
        overlayManager.add(lockedItemOverlay);
        overlayManager.add(clogHighlightOverlay);
        overlayManager.add(gritToastOverlay);
        overlayManager.add(trialsOverlay);
        partySyncService.startUp();
        clogDataService.ensureLoaded();

        clientThread.invoke(() -> {
            if (client.getGameState() == GameState.LOGGED_IN) {
                if (client.getLocalPlayer() != null) {
                    sessionState.setCurrentCharacter(client.getLocalPlayer().getName());
                }
                onSeasonalWorld = isSeasonalWorld(client.getWorld());
                sessionState.setSeasonalWorld(onSeasonalWorld);
                // A player can not be a bronzeman on a seasonal world.
                if (!onSeasonalWorld) {
                    setChatboxName(getNameChatbox());
                }
            }
        });
    }

    @Override
    protected void shutDown() throws Exception {
        super.shutDown();
        groupState.removeListener(groupStateListener);
        groupState.close();
        overlayManager.remove(CrabmanModeOverlay);
        chatCommandManager.unregisterCommand(TB_UNLOCKS_STRING);
        chatCommandManager.unregisterCommand(TB_RECENT_STRING);
        chatCommandManager.unregisterCommand(TB_CLOG_DEBUG_STRING);
        chatCommandManager.unregisterCommand(TB_GRIT_STRING);
        chatCommandManager.unregisterCommand(TB_GRANT_STRING);
        eventBus.unregister(obtainedSyncService);
        eventBus.unregister(dropAttributionService);
        eventBus.unregister(clogUnlockDetector);
        eventBus.unregister(trialService);
        eventBus.unregister(unlockCoordinator);
        eventBus.unregister(gritService);
        eventBus.unregister(partySyncService);
        eventBus.unregister(geEnforcementService);
        eventBus.unregister(equipEnforcementService);
        eventBus.unregister(tradeWarningService);
        eventBus.unregister(clogMenuService);
        overlayManager.remove(tradeWarningOverlay);
        overlayManager.remove(lockedItemOverlay);
        overlayManager.remove(clogHighlightOverlay);
        overlayManager.remove(gritToastOverlay);
        overlayManager.remove(trialsOverlay);
        partySyncService.shutDown();
        clientToolbar.removeNavigation(navButton);
        clientThread.invoke(() -> {
            // Cleanup is not required after having played on a seasonal world.
            if (client.getGameState() == GameState.LOGGED_IN && !onSeasonalWorld) {
                setChatboxName(getNameDefault());
            }
        });
    }

    /** Loads players unlocks on login **/
    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        if (e.getGameState() == GameState.LOGGED_IN) {
            loadResources();

            onSeasonalWorld = isSeasonalWorld(client.getWorld());
            sessionState.setSeasonalWorld(onSeasonalWorld);
            clogDataService.ensureLoaded();

        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent) {
        if (scriptCallbackEvent.getEventName().equals(SCRIPT_EVENT_SET_CHATBOX_INPUT) && !onSeasonalWorld) {
            setChatboxName(getNameChatbox());
        }
    }

    @SuppressWarnings("incomplete-switch")
    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (client.getGameState() != GameState.LOADING && client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        String name = Text.removeTags(chatMessage.getName());
        switch (chatMessage.getType()) {
            case PRIVATECHAT:
            case MODPRIVATECHAT:
                // Note this is unable to change icon on PMs if they are not a friend or in
                // friends chat
            case CLAN_CHAT:
            case CLAN_GUEST_CHAT:
            case FRIENDSCHAT:
                if (isChatPlayerOnNormalWorld(name) && isChatPlayerBronzeman(name)) {
                    addBronzemanIconToMessage(chatMessage);
                }
                break;
            case PUBLICCHAT:
            case MODCHAT:
                if (!onSeasonalWorld && isChatPlayerBronzeman(name)) {
                    addBronzemanIconToMessage(chatMessage);
                }
                break;
        }
    }

    @Subscribe
    public void onPlayerChanged(PlayerChanged event) {
        Player player = client.getLocalPlayer();
        sessionState.setCurrentCharacter(player == null ? "" : player.getName());

        // First character to log in claims the mode; changeable in settings.
        String name = player == null ? null : player.getName();
        if (name != null && !name.isEmpty() && config.enableCrabman().trim().isEmpty() && !onSeasonalWorld) {
            configManager.setConfiguration(CONFIG_GROUP, "enableCrabman", name);
            sendChatMessage("Trialbound enabled for " + name
                    + ". Change the character name in the plugin settings if this is the wrong account.");
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals(CONFIG_GROUP)) {
            if (event.getKey().equals("namesBronzeman")) {
                updateNamesBronzeman();
            } else if (event.getKey().equals("enableCrabman")) {
                updateAllowedCrabman();
            } else if (event.getKey().equals("partyPassphrase")) {
                initializeGroupState();
                partySyncService.maybeJoinParty();
            }
        }
    }

    private boolean isLoggedIntoCrabman() {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return false;
        }

        String playerName = player.getName();
        if (playerName == null || playerName.isEmpty() || enabledCrabman == null || enabledCrabman.isEmpty()) {
            return false;
        }
        return playerName.equals(enabledCrabman);
    }

    public void sendChatMessage(String chatMessage) {
        final String message = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append(chatMessage)
                .build();

        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(message)
                        .build());
    }

    private void updateNamesBronzeman() {
        namesBronzeman = Text.fromCSV(config.namesBronzeman());
    }

    private void updateAllowedCrabman() {
        enabledCrabman = config.enableCrabman();
        sessionState.setEnabledCharacter(enabledCrabman);
        // Note: Semi-unsafe to send null but since we don't use the event it should be
        // fine
        onPlayerChanged(null);
    }

    /**
     * (Re)loads the local event store for the group derived from the party
     * passphrase. Local-first: this always succeeds; transports sync later.
     */
    private void initializeGroupState() {
        String groupKey = GroupStateService.deriveGroupKey(config.partyPassphrase());
        if (groupKey.equals(groupState.getGroupKey()) && groupState.isReady()) {
            return;
        }
        groupState.initialize(groupKey);
        if (panel != null) {
            javax.swing.SwingUtilities.invokeLater(panel::refreshAll);
        }
    }

    /**
     * Adds the Bronzeman Icon in front of player names.
     *
     * @param chatMessage chat message to edit sender name on
     */
    private void addBronzemanIconToMessage(ChatMessage chatMessage) {
        String name = chatMessage.getName();
        if (!name.equals(Text.removeTags(name))) {
            // If the name has any tags, no bronzeman icon will be added.
            // This makes it so Iron men can't be flagged as bronzeman, but
            // currently also excludes mods.
            return;
        }

        final MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setName(getNameWithIcon(bronzemanIconOffset, name));

        client.refreshChat();
    }

    /**
     * Checks if the world is a Seasonal world (Like leagues and seasonal deadman).
     *
     * @param worldNumber number of the world to check.
     * @return boolean true/false if it is a seasonal world or not.
     */
    private boolean isSeasonalWorld(int worldNumber) {
        WorldResult worlds = worldService.getWorlds();
        if (worlds == null) {
            return false;
        }

        World world = worlds.findWorld(worldNumber);
        return world != null && world.getTypes().contains(WorldType.SEASONAL);
    }

    /**
     * Checks if the given message was sent by the player
     *
     * @param chatMessage number of the world to check.
     * @return boolean true/false if the message was sent by the player.
     */
    private boolean sentByPlayer(ChatMessage chatMessage) {
        MessageNode messageNode = chatMessage.getMessageNode();

        return Text.sanitize(messageNode.getName()).equals(Text.sanitize(client.getLocalPlayer().getName()));
    }

    /**
     * Update the player name in the chatbox input
     */
    private void setChatboxName(String name) {
        Widget chatboxInput = client.getWidget(InterfaceID.Chatbox.INPUT);
        if (chatboxInput != null) {
            String text = chatboxInput.getText();
            int idx = text.indexOf(':');
            if (idx != -1) {
                String newText = name + text.substring(idx);
                chatboxInput.setText(newText);
            }
        }
    }

    /**
     * Gets the bronzeman name, including possible icon, of the local player.
     *
     * @return String of icon + name
     */
    private String getNameChatbox() {
        Player player = client.getLocalPlayer();
        if (player != null) {
            Widget chatboxInput = client.getWidget(InterfaceID.Chatbox.INPUT);
            String namePlusChannel = player.getName();
            if (chatboxInput != null) {
                String text = chatboxInput.getText();
                int idx = text.indexOf(':');
                if (idx != -1) {
                    namePlusChannel = text.substring(0, idx);
                }
            }
            return getNameWithIcon(bronzemanIconOffset, namePlusChannel);
        }
        return null;
    }

    /**
     * Gets the default name, including possible icon, of the local player.
     *
     * @return String of icon + name
     */
    private String getNameDefault() {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return null;
        }

        int iconIndex;
        int accountType = client.getVarbitValue(VarbitID.IRONMAN);
        switch (accountType) {
            case 1: // Ironman
                iconIndex = IconID.IRONMAN.getIndex();
                break;
            case 2: // Ultimate Ironman
                iconIndex = IconID.ULTIMATE_IRONMAN.getIndex();
                break;
            case 3: // Hardcore Ironman
                iconIndex = IconID.HARDCORE_IRONMAN.getIndex();
                break;
            default:
                return player.getName();
        }

        return getNameWithIcon(iconIndex, player.getName());
    }

    /**
     * Get a name formatted with icon
     *
     * @param iconIndex index of the icon
     * @param name      name of the player
     * @return String of icon + name
     */
    private static String getNameWithIcon(int iconIndex, String name) {
        String icon = "<img=" + iconIndex + ">";
        return icon + name;
    }

    /**
     * Gets a ChatPlayer object from a clean name by searching friends chat and
     * friends list.
     *
     * @param name name of player to find.
     * @return ChatPlayer if found, else null.
     */
    private ChatPlayer getChatPlayerFromName(String name) {
        // Search friends chat members first, because if a friend is in the friends chat
        // but their private
        // chat is 'off', then we won't know the world
        FriendsChatManager friendsChatManager = client.getFriendsChatManager();
        if (friendsChatManager != null) {
            FriendsChatMember friendsChatMember = friendsChatManager.findByName(name);
            if (friendsChatMember != null) {
                return friendsChatMember;
            }
        }

        NameableContainer<Friend> friendContainer = client.getFriendContainer();
        return friendContainer.findByName(name);
    }

    /**
     * Checks if a player name is a friend or friends chat member is a bronzeman.
     *
     * @param name name of player to check.
     * @return boolean true/false.
     */
    private boolean isChatPlayerBronzeman(String name) {
        return isChatPlayerOnNormalWorld(name)
                && (namesBronzeman.contains(name) || namesBronzeman.contains(name.replace('\u00A0', ' ')));
    }

    /**
     * Checks if a player name is a friend or friends chat member on a normal world.
     *
     * @param name name of player to check.
     * @return boolean true/false.
     */
    private boolean isChatPlayerOnNormalWorld(String name) {
        ChatPlayer player = getChatPlayerFromName(name);

        if (player == null) {
            return true;
        }

        int world = player.getWorld();
        return !isSeasonalWorld(world);
    }

    private void onClogDebugCommand(ChatMessage chatMessage, String message) {
        if (!sentByPlayer(chatMessage)) {
            return;
        }
        if (!clogDataService.isLoaded()) {
            sendChatMessage("Collection log data is not loaded yet.");
            return;
        }
        int pages = clogDataService.getAllPages().size();
        int items = clogDataService.getAllClogItemIds().size();
        sendChatMessage("Trialbound clog data: " + pages + " pages, " + items + " items.");
    }

    private void onGrantCommand(ChatMessage chatMessage, String message) {
        if (!sentByPlayer(chatMessage) || !isLoggedIntoCrabman()) {
            return;
        }
        int amount = 10_000;
        String[] parts = message.trim().split("\\s+");
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                sendChatMessage("Usage: " + TB_GRANT_STRING + " <amount>");
                return;
            }
        }
        groupState.addAdminGrit(amount);
        sendChatMessage("Granted " + amount + " Grit (testing). Pooled: " + groupState.getPooledGrit() + ".");
    }

    private void onGritCommand(ChatMessage chatMessage, String message) {
        if (!sentByPlayer(chatMessage) || !isLoggedIntoCrabman()) {
            return;
        }
        String player = client.getLocalPlayer().getName();
        sendChatMessage("Grit: " + groupState.getGritBalance(player) + " yours, "
                + groupState.getPooledGrit() + " pooled.");
    }

    private void OnUnlocksCountCommand(ChatMessage chatMessage, String message) {
        if (!sentByPlayer(chatMessage) || !isLoggedIntoCrabman()) {
            return;
        }

        Map<Integer, TbEventRecord> unlockedItems = groupState.getUnlockedItems();

        Collection<TbEventRecord> unlocked = unlockedItems.values();
        long unlockedByMe = unlocked.stream()
                .filter((item) -> client.getLocalPlayer().getName().equals(item.getPlayer())).count();

        final ChatMessageBuilder builder = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append("Your group has unlocked ")
                .append(ChatColorType.NORMAL)
                .append(Integer.toString(unlocked.size()))
                .append(" items (" + unlockedByMe + " by you)")
                .append(ChatColorType.HIGHLIGHT)
                .append(".");

        String response = builder.build();

        MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setRuneLiteFormatMessage(response);
        client.refreshChat();
    }

    private void OnRecentUnlocksCommand(ChatMessage chatMessage, String message) {
        if (!sentByPlayer(chatMessage) || !isLoggedIntoCrabman()) {
            return;
        }

        Map<Integer, TbEventRecord> unlockedItems = groupState.getUnlockedItems();

        Collection<TbEventRecord> unlocked = unlockedItems.values();

        final ChatMessageBuilder builder = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append("Your group has recently unlocked: ")
                .append(ChatColorType.NORMAL)
                .append(unlocked.stream()
                        .sorted(Comparator.comparingLong(TbEventRecord::getCreatedOn).reversed())
                        .limit(5)
                        .map(TbEventRecord::getItemName)
                        .collect(Collectors.joining(", ")));

        String response = builder.build();

        MessageNode messageNode = chatMessage.getMessageNode();
        messageNode.setRuneLiteFormatMessage(response);
        client.refreshChat();
    }

    /**
     * Loads the bronzeman resources into the client.
     */
    private void loadResources() {
        final IndexedSprite[] modIcons = client.getModIcons();

        if (bronzemanIconOffset != -1 || modIcons == null) {
            return;
        }

        unlockImage = ImageUtil.loadImageResource(getClass(), "/item-unlocked.png");
        BufferedImage image = ImageUtil.loadImageResource(getClass(), "/bronzeman_icon.png");
        IndexedSprite indexedSprite = ImageUtil.getImageIndexedSprite(image, client);

        bronzemanIconOffset = modIcons.length;

        final IndexedSprite[] newModIcons = Arrays.copyOf(modIcons, modIcons.length + 1);
        newModIcons[newModIcons.length - 1] = indexedSprite;

        client.setModIcons(newModIcons);
    }

}
