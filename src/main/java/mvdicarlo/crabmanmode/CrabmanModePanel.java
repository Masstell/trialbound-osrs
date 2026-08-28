package mvdicarlo.crabmanmode;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import mvdicarlo.crabmanmode.ui.GritTabPanel;
import mvdicarlo.crabmanmode.ui.TrialsTabPanel;
import mvdicarlo.crabmanmode.ui.UnlocksTabPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/** Trialbound side panel: Trials / Grit / Unlocks tabs with a status strip. */
@Singleton
public class CrabmanModePanel extends PluginPanel {
    private final JLabel statusLabel = new JLabel(" ");
    private final TrialsTabPanel trialsTab;
    private final GritTabPanel gritTab;
    private final UnlocksTabPanel unlocksTab;

    @Inject
    public CrabmanModePanel(TrialsTabPanel trialsTab, GritTabPanel gritTab, UnlocksTabPanel unlocksTab) {
        this.trialsTab = trialsTab;
        this.gritTab = gritTab;
        this.unlocksTab = unlocksTab;

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
    }

    /** One-line status under the title (Swing thread). */
    public void displayStatus(String message) {
        statusLabel.setText("<html><font size='2'>" + message + "</font></html>");
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
        refreshTrials();
        refreshGrit();
        refreshUnlocks();
    }
}
