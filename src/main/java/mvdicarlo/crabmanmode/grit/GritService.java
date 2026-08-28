package mvdicarlo.crabmanmode.grit;

import java.util.OptionalInt;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.clog.ClogPage;
import mvdicarlo.crabmanmode.events.ClogDropResolved;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.PurchaseResult;
import mvdicarlo.crabmanmode.trial.BossTierRegistry;
import mvdicarlo.crabmanmode.trial.TrialService;
import mvdicarlo.crabmanmode.trial.TrialSlot;
import mvdicarlo.crabmanmode.trial.TrialTier;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Grit economy: awards grit for on-trial clog drops (new or duplicate;
 * off-trial drops earn nothing) and prices unlock purchases by the item's
 * hardest source tier.
 */
@Slf4j
@Singleton
public class GritService {
    private final CrabmanModeConfig config;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final BossTierRegistry registry;
    private final TrialService trialService;
    private final GroupStateService groupState;
    private final TrialboundChat chat;

    @Inject
    public GritService(CrabmanModeConfig config, SessionState sessionState, ClogDataService clogData,
            BossTierRegistry registry, TrialService trialService, GroupStateService groupState, TrialboundChat chat) {
        this.config = config;
        this.sessionState = sessionState;
        this.clogData = clogData;
        this.registry = registry;
        this.trialService = trialService;
        this.groupState = groupState;
        this.chat = chat;
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
        int base = override.isPresent() ? override.getAsInt() : baseFor(registry.getTier(pageName));
        int delta = base * slot.getMultiplierPercent() / 100;
        if (delta <= 0) {
            return;
        }
        groupState.addTrialGrit(event.getItemId(), delta, slot.trialKey(), slot.getMultiplierPercent());
        chat.send("+" + delta + " Grit - " + pageName + " (" + slot.getType().getDisplayName() + " "
                + slot.getType().getMultiplierLabel() + "). Pooled: " + groupState.getPooledGrit() + ".");
    }

    private int baseFor(TrialTier tier) {
        switch (tier) {
            case EASY:
                return config.gritBaseEasy();
            case MEDIUM:
                return config.gritBaseMedium();
            case HARD:
                return config.gritBaseHard();
            case RAID:
                return config.gritBaseRaid();
            default:
                return 0;
        }
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
        switch (bestTier) {
            case EASY:
                return config.priceEasy();
            case MEDIUM:
                return config.priceMedium();
            case HARD:
                return config.priceHard();
            case RAID:
                return config.priceRaid();
            default:
                return config.priceNonBoss();
        }
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
