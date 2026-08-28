package mvdicarlo.crabmanmode.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.Duration;
import java.time.Instant;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.trial.TrialService;
import mvdicarlo.crabmanmode.trial.TrialSlot;
import net.runelite.client.ui.ColorScheme;

/** The five active trials: slot, boss, countdown, grit earned this period. */
@Singleton
public class TrialsTabPanel extends JPanel {
    private final TrialService trialService;
    private final GroupStateService groupState;
    private final JPanel cards = new JPanel();

    @Inject
    public TrialsTabPanel(TrialService trialService, GroupStateService groupState) {
        this.trialService = trialService;
        this.groupState = groupState;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        cards.setLayout(new GridLayout(0, 1, 0, 6));
        cards.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(cards, BorderLayout.NORTH);
        // Countdown labels tick over on a pure-Swing timer; no client access.
        new Timer(30_000, e -> refresh()).start();
        refresh();
    }

    public void refresh() {
        cards.removeAll();
        if (trialService.getActiveTrials().isEmpty()) {
            cards.add(new JLabel("<html><i>Trials appear once collection log data loads (log in).</i></html>"));
        } else {
            Instant now = Instant.now();
            for (TrialSlot slot : trialService.getActiveTrials()) {
                cards.add(buildCard(slot, now));
            }
        }
        cards.revalidate();
        cards.repaint();
    }

    private JPanel buildCard(TrialSlot slot, Instant now) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        int earned = groupState.getGritEarnedForTrial(slot.trialKey());
        JLabel label = new JLabel("<html><b>" + slot.getType().getDisplayName() + " "
                + slot.getType().getMultiplierLabel() + "</b><br>"
                + slot.getPageName() + "<br><font color='#a0a0a0'>Ends in "
                + formatRemaining(Duration.between(now, slot.getPeriodEndUtc()))
                + " &middot; Earned " + earned + " Grit</font></html>");
        card.add(label, BorderLayout.CENTER);
        return card;
    }

    private static String formatRemaining(Duration remaining) {
        if (remaining.isNegative()) {
            return "now";
        }
        long days = remaining.toDays();
        long hours = remaining.minusDays(days).toHours();
        long minutes = remaining.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
