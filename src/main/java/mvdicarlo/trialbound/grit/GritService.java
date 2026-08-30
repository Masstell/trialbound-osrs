package mvdicarlo.trialbound.grit;

import java.util.OptionalInt;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.trialbound.SessionState;
import mvdicarlo.trialbound.TrialboundChat;
import mvdicarlo.trialbound.clog.ClogDataService;
import mvdicarlo.trialbound.clog.ClogPage;
import mvdicarlo.trialbound.events.ClogDropResolved;
import mvdicarlo.trialbound.store.GroupStateService;
import mvdicarlo.trialbound.store.PurchaseResult;
import mvdicarlo.trialbound.clog.ClogText;
import mvdicarlo.trialbound.trial.BossTierRegistry;
import mvdicarlo.trialbound.trial.TrialService;
import mvdicarlo.trialbound.trial.TrialSlot;
import mvdicarlo.trialbound.trial.TrialTier;
import mvdicarlo.trialbound.trial.TrialType;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.loottracker.LootReceived;

/**
 * The Grit economy: awards grit for on-trial clog drops (new or duplicate;
 * off-trial drops earn nothing) and prices unlock purchases by the item's
 * hardest source tier.
 */
@Slf4j
@Singleton
public class GritService {
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final BossTierRegistry registry;
    private final TrialService trialService;
    private final GroupStateService groupState;
    private final TrialboundChat chat;
    private final mvdicarlo.trialbound.ui.GritToastOverlay toastOverlay;

    @Inject
    public GritService(SessionState sessionState, ClogDataService clogData,
            BossTierRegistry registry, TrialService trialService, GroupStateService groupState, TrialboundChat chat,
            mvdicarlo.trialbound.ui.GritToastOverlay toastOverlay) {
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.registry = registry;
        this.trialService = trialService;
        this.groupState = groupState;
        this.chat = chat;
        this.toastOverlay = toastOverlay;
    }

    /**
     * TEST HOOK, verified working 2026-08-28: egg potatoes from the Grubby
     * Chest pay grit at the daily rate, proving that chest loot
     * (grubby/Larran's style) flows through real loot events with no kill
     * credit involved anywhere. Kept disabled for future pipeline testing -
     * flip the flag and rebuild to re-enable.
     */
    private static final boolean TEST_CHEST_GRIT_ENABLED = false;
    private static final int TEST_CHEST_ITEM_ID = ItemID.POTATO_EGG_TOMATO; // Egg potato
    private static final String TEST_CHEST_SOURCE = "grubby chest";

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!TEST_CHEST_GRIT_ENABLED) {
            return;
        }
        if (!sessionState.isActive() || !TEST_CHEST_SOURCE.equals(ClogText.normalize(event.getName()))) {
            return;
        }
        boolean hasTestItem = event.getItems().stream()
                .anyMatch(stack -> stack.getId() == TEST_CHEST_ITEM_ID);
        if (!hasTestItem) {
            return;
        }
        TrialSlot daily = trialService.getActiveTrials().stream()
                .filter(s -> s.getType() == TrialType.DAILY).findFirst().orElse(null);
        if (daily == null) {
            return;
        }
        int delta = GritEconomy.baseGrit(TrialTier.EASY) * daily.getMultiplierPercent() / 100;
        groupState.addTrialGrit(TEST_CHEST_ITEM_ID, delta, daily.trialKey(), daily.getMultiplierPercent());
        String summary = "+" + delta + " Grit - Grubby Chest egg potato (chest test)";
        toastOverlay.push(summary);
        chat.send(summary + ". Pooled: " + groupState.getPooledGrit() + ".");
        log.info("TEST: Grubby Chest egg potato paid {} grit via loot event (no kill credit)", delta);
    }

    @Subscribe
    public void onClogDropResolved(ClogDropResolved event) {
        if (!sessionState.isActive() || event.getPageName() == null) {
            return;
        }
        TrialSlot slot = trialService.getSlotForPage(event.getPageName()).orElse(null);
        if (slot == null) {
            return; // off-trial: the unlock still happened, no grit
        }
        String pageName = event.getPageName();
        OptionalInt override = registry.getGritBaseOverride(pageName);
        int base = override.isPresent() ? override.getAsInt() : GritEconomy.baseGrit(registry.getTier(pageName));
        int delta = base * slot.getMultiplierPercent() / 100;
        if (delta <= 0) {
            return;
        }
        groupState.addTrialGrit(event.getItemId(), delta, slot.trialKey(), slot.getMultiplierPercent());
        String summary = "+" + delta + " Grit - " + pageName + " (" + slot.getType().getDisplayName() + " "
                + slot.getType().getMultiplierLabel() + ")";
        toastOverlay.push(summary);
        chat.send(summary + ". Pooled: " + groupState.getPooledGrit() + ".");
    }

    /**
     * Unlock price for a clog item: per-page override of its hardest-tier
     * source if set, else the config price for that tier; items with no
     * tiered source use the non-boss price.
     */
    public int getPrice(int itemId) {
        TrialTier bestTier = TrialTier.NON_BOSS;
        String bestPage = null;
        for (ClogPage page : clogData.getPagesForItem(itemId)) {
            TrialTier tier = registry.getTier(page.getName());
            if (tier.getRank() > bestTier.getRank()) {
                bestTier = tier;
                bestPage = page.getName();
            }
        }
        if (bestPage != null) {
            OptionalInt override = registry.getPriceOverride(bestPage);
            if (override.isPresent()) {
                return override.getAsInt();
            }
        }
        return GritEconomy.unlockPrice(bestTier);
    }

    /** Buys an unlock with pooled grit at the computed price. */
    public PurchaseResult purchaseUnlock(int itemId, String itemName) {
        if (!sessionState.isActive()) {
            return PurchaseResult.NOT_READY;
        }
        if (!clogData.isClogItem(itemId)) {
            return PurchaseResult.NOT_READY;
        }
        PurchaseResult result = groupState.purchase(itemId, itemName, getPrice(itemId));
        log.debug("Purchase {} ({}) -> {}", itemName, itemId, result);
        return result;
    }
}
