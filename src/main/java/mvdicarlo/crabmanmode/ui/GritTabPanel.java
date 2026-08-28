package mvdicarlo.crabmanmode.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.GritReason;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import net.runelite.client.ui.ColorScheme;

/** Grit balances (yours, pooled, per member) and the recent ledger. */
@Singleton
public class GritTabPanel extends JPanel {
    private static final int LEDGER_LIMIT = 15;

    private final GroupStateService groupState;
    private final SessionState sessionState;
    private final ClogDataService clogData;
    private final JPanel content = new JPanel();

    @Inject
    public GritTabPanel(GroupStateService groupState, SessionState sessionState, ClogDataService clogData) {
        this.groupState = groupState;
        this.sessionState = sessionState;
        this.clogData = clogData;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.setLayout(new GridLayout(0, 1, 0, 4));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(content, BorderLayout.NORTH);
        refresh();
    }

    public void refresh() {
        content.removeAll();

        String me = sessionState.getCurrentCharacter();
        JLabel yours = new JLabel("Your Grit: " + (me.isEmpty() ? 0 : groupState.getGritBalance(me)));
        yours.setFont(yours.getFont().deriveFont(Font.BOLD, 16f));
        content.add(yours);
        JLabel pooled = new JLabel("Pooled Grit: " + groupState.getPooledGrit());
        pooled.setFont(pooled.getFont().deriveFont(Font.BOLD, 14f));
        content.add(pooled);

        Map<String, Integer> balances = groupState.getBalancesByPlayer();
        if (balances.size() > 1) {
            for (Map.Entry<String, Integer> entry : balances.entrySet()) {
                content.add(new JLabel("<html><font color='#a0a0a0'>" + entry.getKey() + ": "
                        + entry.getValue() + "</font></html>"));
            }
        }

        JLabel header = new JLabel("Recent");
        header.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        content.add(header);

        Instant now = Instant.now();
        for (TbEventRecord event : groupState.getRecentGritEvents(LEDGER_LIMIT)) {
            content.add(new JLabel("<html>" + describe(event) + " <font color='#808080'>"
                    + relative(now, event.createdInstant()) + "</font></html>"));
        }
        content.revalidate();
        content.repaint();
    }

    private String describe(TbEventRecord event) {
        String sign = event.getDelta() >= 0 ? "<font color='#80ff80'>+" : "<font color='#ff8080'>";
        String what;
        if (event.getReason() == GritReason.PURCHASE && event.getItemId() != null) {
            what = "unlocked " + clogData.getItemName(event.getItemId());
        } else if (event.getItemId() != null) {
            what = clogData.getItemName(event.getItemId());
        } else {
            what = event.getReason().name().toLowerCase();
        }
        return sign + event.getDelta() + "</font> " + event.getPlayer() + " &middot; " + what;
    }

    private static String relative(Instant now, Instant then) {
        Duration d = Duration.between(then, now);
        if (d.toDays() > 0) {
            return d.toDays() + "d ago";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h ago";
        }
        return Math.max(0, d.toMinutes()) + "m ago";
    }
}
