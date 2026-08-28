package mvdicarlo.crabmanmode.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import lombok.Value;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.clog.ClogDataService;
import mvdicarlo.crabmanmode.clog.ClogPage;
import mvdicarlo.crabmanmode.clog.ClogTab;
import mvdicarlo.crabmanmode.grit.GritService;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.PurchaseResult;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Browser over every collection log item: locked/unlocked state, prices, BUY
 * via right-click, re-lock for unlocked items. Complements the in-clog
 * overlay, which is the primary spend surface.
 */
@Singleton
public class UnlocksTabPanel extends JPanel {
    private static final int COLUMNS = 5;
    private static final int RENDER_CAP = 200;

    private final ClogDataService clogData;
    private final GroupStateService groupState;
    private final GritService gritService;
    private final ItemManager itemManager;
    private final ClientThread clientThread;
    private final TrialboundChat chat;
    private final SessionState sessionState;

    private final JLabel gritHeader = new JLabel();
    private final IconTextField searchField = new IconTextField();
    private final JComboBox<String> tabFilter = new JComboBox<>();
    private final JComboBox<String> stateFilter = new JComboBox<>(new String[] { "All", "Locked", "Unlocked" });
    private final JComboBox<String> sortOrder = new JComboBox<>(
            new String[] { "A-Z", "Z-A", "Price high", "Price low" });
    private final JPanel grid = new JPanel();
    private final JLabel footer = new JLabel();

    @Inject
    public UnlocksTabPanel(ClogDataService clogData, GroupStateService groupState, GritService gritService,
            ItemManager itemManager, ClientThread clientThread, TrialboundChat chat, SessionState sessionState) {
        this.clogData = clogData;
        this.groupState = groupState;
        this.gritService = gritService;
        this.itemManager = itemManager;
        this.clientThread = clientThread;
        this.chat = chat;
        this.sessionState = sessionState;

        setLayout(new BorderLayout(0, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        tabFilter.addItem("All tabs");
        for (ClogTab tab : ClogTab.values()) {
            tabFilter.addItem(tab.getDisplayName());
        }

        JPanel controls = new JPanel(new GridLayout(0, 1, 0, 4));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.add(gritHeader);
        searchField.setIcon(IconTextField.Icon.SEARCH);
        searchField.addActionListener(e -> refresh());
        searchField.addClearListener(this::refresh);
        controls.add(searchField);
        controls.add(tabFilter);
        controls.add(stateFilter);
        controls.add(sortOrder);
        tabFilter.addActionListener(e -> refresh());
        stateFilter.addActionListener(e -> refresh());
        sortOrder.addActionListener(e -> refresh());
        add(controls, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        center.add(grid, BorderLayout.NORTH);
        footer.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        center.add(footer, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
    }

    public void refresh() {
        String me = sessionState.getCurrentCharacter();
        int mine = me.isEmpty() ? 0 : groupState.getGritBalance(me);
        gritHeader.setText("<html><b><font color='#ffc83c'>Grit: " + mine + " yours &middot; "
                + groupState.getPooledGrit() + " pooled</font></b></html>");

        if (!clogData.isLoaded()) {
            grid.removeAll();
            grid.setLayout(new GridLayout(1, 1));
            grid.add(new JLabel("<html><i>Log in to load collection log data.</i></html>"));
            footer.setText("");
            revalidate();
            repaint();
            return;
        }

        Map<Integer, TbEventRecord> unlocked = groupState.getUnlockedItems();
        String search = searchField.getText() == null ? "" : searchField.getText().toLowerCase().trim();
        int tabIndex = tabFilter.getSelectedIndex(); // 0 = all
        String state = (String) stateFilter.getSelectedItem();

        List<Row> rows = new ArrayList<>();
        for (int itemId : clogData.getAllClogItemIds()) {
            boolean isUnlocked = unlocked.containsKey(itemId);
            if ("Locked".equals(state) && isUnlocked) {
                continue;
            }
            if ("Unlocked".equals(state) && !isUnlocked) {
                continue;
            }
            String name = clogData.getItemName(itemId);
            if (!search.isEmpty() && !name.toLowerCase().contains(search)) {
                continue;
            }
            if (tabIndex > 0 && !isInTab(itemId, ClogTab.values()[tabIndex - 1])) {
                continue;
            }
            rows.add(new Row(itemId, name, isUnlocked,
                    isUnlocked ? 0 : gritService.getPrice(itemId),
                    isUnlocked ? unlocked.get(itemId).getPlayer() : null));
        }

        sortRows(rows);
        int total = rows.size();
        if (rows.size() > RENDER_CAP) {
            rows = rows.subList(0, RENDER_CAP);
        }

        grid.removeAll();
        grid.setLayout(new GridLayout(0, COLUMNS, 1, 1));
        for (Row row : rows) {
            grid.add(buildCell(row));
        }
        footer.setText(total > RENDER_CAP
                ? "Showing " + RENDER_CAP + " of " + total + " - refine your search"
                : total + " items");
        revalidate();
        repaint();
    }

    private boolean isInTab(int itemId, ClogTab tab) {
        for (ClogPage page : clogData.getPagesForItem(itemId)) {
            if (page.getTab() == tab) {
                return true;
            }
        }
        return false;
    }

    private void sortRows(List<Row> rows) {
        switch ((String) sortOrder.getSelectedItem()) {
            case "Z-A":
                rows.sort(Comparator.comparing(Row::getName, String.CASE_INSENSITIVE_ORDER).reversed());
                break;
            case "Price high":
                rows.sort(Comparator.comparingInt(Row::getPrice).reversed()
                        .thenComparing(Row::getName, String.CASE_INSENSITIVE_ORDER));
                break;
            case "Price low":
                rows.sort(Comparator.comparingInt(Row::getPrice)
                        .thenComparing(Row::getName, String.CASE_INSENSITIVE_ORDER));
                break;
            default:
                rows.sort(Comparator.comparing(Row::getName, String.CASE_INSENSITIVE_ORDER));
                break;
        }
    }

    private JLabel buildCell(Row row) {
        JLabel cell = new JLabel();
        cell.setHorizontalAlignment(JLabel.CENTER);
        AsyncBufferedImage icon = itemManager.getImage(row.getItemId());
        if (row.isUnlocked()) {
            icon.addTo(cell);
            cell.setToolTipText("<html>" + row.getName() + "<br>Unlocked by " + row.getUnlockedBy() + "</html>");
        } else {
            icon.onLoaded(() -> {
                cell.setIcon(new ImageIcon(ImageUtil.grayscaleImage(icon)));
                cell.repaint();
            });
            cell.setToolTipText("<html>" + row.getName() + "<br>Locked - " + row.getPrice() + " Grit</html>");
        }
        cell.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    buildPopup(row).show(cell, e.getX(), e.getY());
                }
            }
        });
        return cell;
    }

    private JPopupMenu buildPopup(Row row) {
        JPopupMenu popup = new JPopupMenu();
        if (row.isUnlocked()) {
            JMenuItem relock = new JMenuItem("Re-lock " + row.getName());
            relock.addActionListener(e -> {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Re-lock " + row.getName() + " for the whole group?", "Trialbound",
                        JOptionPane.OK_CANCEL_OPTION);
                if (choice == JOptionPane.OK_OPTION) {
                    groupState.relock(row.getItemId());
                    refresh();
                }
            });
            popup.add(relock);
        } else {
            JMenuItem unlock = new JMenuItem("Unlock for " + row.getPrice() + " Grit");
            unlock.addActionListener(e -> {
                int choice = JOptionPane.showConfirmDialog(this,
                        "Unlock " + row.getName() + " for " + row.getPrice() + " Grit?\nPooled Grit: "
                                + groupState.getPooledGrit(),
                        "Trialbound unlock", JOptionPane.OK_CANCEL_OPTION);
                if (choice != JOptionPane.OK_OPTION) {
                    return;
                }
                PurchaseResult result = gritService.purchaseUnlock(row.getItemId(), row.getName());
                if (result != PurchaseResult.SUCCESS) {
                    String message = result == PurchaseResult.INSUFFICIENT_GRIT
                            ? "Not enough pooled Grit (need " + row.getPrice() + ", have "
                                    + groupState.getPooledGrit() + ")."
                            : "Purchase failed: " + result + ".";
                    clientThread.invokeLater(() -> chat.send(message));
                }
                refresh();
            });
            popup.add(unlock);
        }
        return popup;
    }

    @Value
    private static class Row {
        int itemId;
        String name;
        boolean unlocked;
        int price;
        String unlockedBy;
    }
}
