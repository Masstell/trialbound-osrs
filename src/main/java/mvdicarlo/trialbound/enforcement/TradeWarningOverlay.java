package mvdicarlo.trialbound.enforcement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Red banner listing locked clog items present in the open trade. */
@Singleton
public class TradeWarningOverlay extends OverlayPanel {
    private final TradeWarningService service;

    @Inject
    public TradeWarningOverlay(TradeWarningService service) {
        this.service = service;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!service.isWarningActive()) {
            return null;
        }
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Locked items in trade!")
                .color(Color.RED)
                .build());
        for (String name : service.getLockedItemNames()) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(name)
                    .leftColor(Color.PINK)
                    .build());
        }
        return super.render(graphics);
    }
}
