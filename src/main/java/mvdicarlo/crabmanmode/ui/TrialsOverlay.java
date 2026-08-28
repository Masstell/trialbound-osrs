package mvdicarlo.crabmanmode.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.trial.TrialService;
import mvdicarlo.crabmanmode.trial.TrialSlot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Optional on-screen list of the five active trials. */
@Singleton
public class TrialsOverlay extends OverlayPanel {
    private final CrabmanModeConfig config;
    private final SessionState sessionState;
    private final TrialService trialService;

    @Inject
    public TrialsOverlay(CrabmanModeConfig config, SessionState sessionState, TrialService trialService) {
        this.config = config;
        this.sessionState = sessionState;
        this.trialService = trialService;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showTrialsOverlay() || !sessionState.isActive() || trialService.getActiveTrials().isEmpty()) {
            return null;
        }
        panelComponent.getChildren().add(TitleComponent.builder().text("Trials").build());
        for (TrialSlot slot : trialService.getActiveTrials()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(slot.getType().getDisplayName() + " " + slot.getType().getMultiplierLabel())
                    .right(slot.getPageName())
                    .rightColor(Color.WHITE)
                    .build());
        }
        return super.render(graphics);
    }
}
