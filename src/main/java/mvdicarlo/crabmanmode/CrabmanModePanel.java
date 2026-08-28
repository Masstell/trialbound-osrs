package mvdicarlo.crabmanmode;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import mvdicarlo.crabmanmode.clog.ObtainedSyncService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.ui.GritTabPanel;
import mvdicarlo.crabmanmode.ui.TrialsTabPanel;
import mvdicarlo.crabmanmode.ui.UnlocksTabPanel;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/** Trialbound side panel: status strip plus Trials / Grit / Unlocks tabs. */
@Singleton
public class CrabmanModePanel extends PluginPanel {
    private final JLabel statusLabel = new JLabel(" ");
    private final TrialsTabPanel trialsTab;
    private final GritTabPanel gritTab;
    private final UnlocksTabPanel unlocksTab;
    private final SessionState sessionState;
    private final GroupStateService groupState;
    private final ObtainedSyncService obtainedSync;
    private final PartyService partyService;
    private final CrabmanModeConfig config;

    @Inject
    public CrabmanModePanel(TrialsTabPanel trialsTab, GritTabPanel gritTab, UnlocksTabPanel unlocksTab,
            SessionState sessionState, GroupStateService groupState, ObtainedSyncService obtainedSync,
            PartyService partyService, CrabmanModeConfig config) {
        this.trialsTab = trialsTab;
        this.gritTab = gritTab;
        this.unlocksTab = unlocksTab;
        this.sessionState = sessionState;
        this.groupState = groupState;
        this.obtainedSync = obtainedSync;
        this.partyService = partyService;
        this.config = config;

        setLayout(new BorderLayout(0, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        JLabel title = new JLabel("Trialbound");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        header.add(title, BorderLayout.NORTH);
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        header.add(statusLabel, BorderLayout.SOUTH);

        JPanel display = new JPanel(new BorderLayout());
        display.setBackground(ColorScheme.DARK_GRAY_COLOR);
        MaterialTabGroup tabGroup = new MaterialTabGroup(display);
        MaterialTab trials = new MaterialTab("Trials", tabGroup, trialsTab);
        MaterialTab grit = new MaterialTab("Grit", tabGroup, gritTab);
        MaterialTab unlocks = new MaterialTab("Unlocks", tabGroup, unlocksTab);
        tabGroup.addTab(trials);
        tabGroup.addTab(grit);
        tabGroup.addTab(unlocks);
        tabGroup.select(trials);

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.setBackground(ColorScheme.DARK_GRAY_COLOR);
        north.add(header, BorderLayout.NORTH);
        north.add(tabGroup, BorderLayout.SOUTH);

        add(north, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);

        // Status reads only thread-safe snapshots; keep it current on a timer.
        new Timer(10_000, e -> refreshStatus()).start();
        refreshStatus();
    }

    /** Rebuilds the status strip from current plugin state (Swing thread). */
    public void refreshStatus() {
        StringBuilder sb = new StringBuilder("<html><font size='2'>");

        String enabled = sessionState.getEnabledCharacter();
        if (enabled.isEmpty()) {
            sb.append("<font color='#ffb84d'>Set your character name in the plugin settings to start.</font>");
        } else if (sessionState.isSeasonalWorld()) {
            sb.append("<font color='#ffb84d'>Seasonal world - Trialbound is paused.</font>");
        } else if (!sessionState.isActiveCharacter()) {
            sb.append("Waiting for <b>").append(enabled).append("</b> to log in.");
        } else {
            sb.append("Active: <b>").append(enabled).append("</b>");

            switch (obtainedSync.getState()) {
                case SYNCED:
                    sb.append("<br>Clog synced: ").append(obtainedSync.getObtainedItems().size())
                            .append(" items obtained");
                    break;
                case STALE:
                    sb.append("<br><font color='#ffb84d'>Open your collection log to re-sync.</font>");
                    break;
                case SYNCING:
                    sb.append("<br>Syncing collection log...");
                    break;
                default:
                    sb.append("<br><font color='#ffb84d'>Open your collection log once to sync.</font>");
                    break;
            }

            if (config.partyPassphrase().trim().isEmpty()) {
                sb.append("<br>Solo mode (no party passphrase set)");
            } else if (partyService.isInParty()) {
                sb.append("<br>Party: ").append(partyService.getMembers().size()).append(" online");
            } else {
                sb.append("<br><font color='#ffb84d'>Joining party...</font>");
            }

            sb.append("<br>Unlocks: ").append(groupState.getUnlockedItems().size())
                    .append(" &middot; Pooled Grit: ").append(groupState.getPooledGrit());
        }

        sb.append("</font></html>");
        statusLabel.setText(sb.toString());
    }

    public void refreshTrials() {
        trialsTab.refresh();
    }

    public void refreshGrit() {
        gritTab.refresh();
    }

    public void refreshUnlocks() {
        unlocksTab.refresh();
    }

    public void refreshAll() {
        refreshStatus();
        refreshTrials();
        refreshGrit();
        refreshUnlocks();
    }
}
