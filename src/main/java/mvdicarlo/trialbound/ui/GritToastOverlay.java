package mvdicarlo.trialbound.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import javax.inject.Inject;
import javax.inject.Singleton;

import mvdicarlo.trialbound.TrialboundConfig;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/** Stacked "+75 Grit - Zulrah (Daily 3x)" toasts, 4 s each, max 3 visible. */
@Singleton
public class GritToastOverlay extends OverlayPanel {
    private static final long TOAST_MS = 4_000;
    private static final int MAX_VISIBLE = 3;
    private static final Color GOLD = new Color(255, 200, 60);

    private final TrialboundConfig config;
    private final Deque<Toast> toasts = new ArrayDeque<>();

    @Inject
    public GritToastOverlay(TrialboundConfig config) {
        this.config = config;
        setPosition(OverlayPosition.TOP_RIGHT);
    }

    public void push(String text) {
        synchronized (toasts) {
            toasts.addLast(new Toast(text, System.currentTimeMillis()));
            while (toasts.size() > MAX_VISIBLE) {
                toasts.removeFirst();
            }
        }
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showGritToasts()) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (toasts) {
            Iterator<Toast> it = toasts.iterator();
            while (it.hasNext()) {
                if (now - it.next().shownAt > TOAST_MS) {
                    it.remove();
                }
            }
            if (toasts.isEmpty()) {
                return null;
            }
            for (Toast toast : toasts) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left(toast.text)
                        .leftColor(GOLD)
                        .build());
            }
        }
        return super.render(graphics);
    }

    private static class Toast {
        final String text;
        final long shownAt;

        Toast(String text, long shownAt) {
            this.text = text;
            this.shownAt = shownAt;
        }
    }
}
