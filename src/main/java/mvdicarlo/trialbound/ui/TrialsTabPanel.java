package mvdicarlo.trialbound.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import mvdicarlo.trialbound.clog.ClogDataService;
import mvdicarlo.trialbound.clog.ClogPage;
import mvdicarlo.trialbound.store.GroupStateService;
import mvdicarlo.trialbound.trial.TrialService;
import mvdicarlo.trialbound.trial.TrialSlot;
import mvdicarlo.trialbound.trial.TrialType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/** Active trials grouped into Monthly / Weekly (easy-medium-hard) / Daily boxes. */
@Singleton
public class TrialsTabPanel extends JPanel {
    private static final int ICON_SIZE = 40;

    /** One box per calendar period, biggest/rarest first; Weekly bundles its three difficulty slots. */
    private static final PeriodDef[] PERIODS = {
            new PeriodDef("Monthly", TrialType.MONTHLY),
            new PeriodDef("Weekly", TrialType.WEEKLY_EASY, TrialType.WEEKLY_MEDIUM, TrialType.WEEKLY_HARD),
            new PeriodDef("Daily", TrialType.DAILY),
    };

    private final TrialService trialService;
    private final GroupStateService groupState;
    private final ClogDataService clogData;
    private final ItemManager itemManager;
    private final JPanel boxes = new JPanel();

    @Inject
    public TrialsTabPanel(TrialService trialService, GroupStateService groupState, ClogDataService clogData,
            ItemManager itemManager) {
        this.trialService = trialService;
        this.groupState = groupState;
        this.clogData = clogData;
        this.itemManager = itemManager;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        boxes.setLayout(new GridLayout(0, 1, 0, 8));
        boxes.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(boxes, BorderLayout.NORTH);
        // Countdown labels tick over on a pure-Swing timer; no client access.
        new Timer(30_000, e -> refresh()).start();
        refresh();
    }

    public void refresh() {
        boxes.removeAll();
        List<TrialSlot> active = trialService.getActiveTrials();
        if (active.isEmpty()) {
            JLabel empty = new JLabel("<html><i>Trials appear once collection log data loads (log in).</i></html>");
            empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            boxes.add(empty);
        } else {
            Instant now = Instant.now();
            for (PeriodDef period : PERIODS) {
                List<TrialSlot> slots = slotsForPeriod(active, period.types);
                if (!slots.isEmpty()) {
                    boxes.add(buildPeriodBox(period, slots, now));
                }
            }
        }
        boxes.revalidate();
        boxes.repaint();
    }

    private static List<TrialSlot> slotsForPeriod(List<TrialSlot> active, TrialType[] types) {
        List<TrialSlot> result = new ArrayList<>(types.length);
        for (TrialType type : types) {
            for (TrialSlot slot : active) {
                if (slot.getType() == type) {
                    result.add(slot);
                    break;
                }
            }
        }
        return result;
    }

    private JPanel buildPeriodBox(PeriodDef period, List<TrialSlot> slots, Instant now) {
        boolean multiSlot = slots.size() > 1;

        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        box.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JPanel titleGroup = new JPanel();
        titleGroup.setOpaque(false);
        titleGroup.setLayout(new BoxLayout(titleGroup, BoxLayout.X_AXIS));
        titleGroup.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel periodLabel = new JLabel(period.label.toUpperCase());
        periodLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        periodLabel.setForeground(Color.WHITE);
        JLabel multLabel = new JLabel(slots.get(0).getType().getMultiplierLabel());
        multLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        multLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        multLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        titleGroup.add(periodLabel);
        titleGroup.add(multLabel);
        header.add(titleGroup);

        JLabel timerLabel = new JLabel(
                "Ends in " + formatRemaining(Duration.between(now, slots.get(0).getPeriodEndUtc())));
        timerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        timerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        timerLabel.setFont(FontManager.getRunescapeSmallFont());
        timerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        timerLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        header.add(timerLabel);

        box.add(header, BorderLayout.NORTH);

        JPanel row = new JPanel(new GridLayout(1, slots.size(), 8, 0));
        row.setOpaque(false);
        for (TrialSlot slot : slots) {
            row.add(buildSlot(slot, multiSlot));
        }
        box.add(row, BorderLayout.CENTER);

        return box;
    }

    /** One boss column within a period box: [tier label] icon, name, grit earned. */
    private JPanel buildSlot(TrialSlot slot, boolean narrow) {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        if (narrow) {
            JLabel tierLabel = new JLabel(tierName(slot.getType()));
            tierLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            tierLabel.setHorizontalAlignment(SwingConstants.CENTER);
            tierLabel.setFont(FontManager.getRunescapeSmallFont());
            tierLabel.setForeground(tierColor(slot.getType()));
            col.add(tierLabel);
        }

        JLabel iconLabel = new JLabel();
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        iconLabel.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        loadIcon(slot.getPageName(), iconLabel);
        col.add(iconLabel);

        JLabel bossLabel = new JLabel(narrow ? wrapCentered(slot.getPageName(), 58) : slot.getPageName());
        bossLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        bossLabel.setHorizontalAlignment(SwingConstants.CENTER);
        bossLabel.setFont(FontManager.getRunescapeSmallFont());
        bossLabel.setForeground(Color.WHITE);
        bossLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        col.add(bossLabel);

        int earned = groupState.getGritEarnedForTrial(slot.trialKey());
        JLabel gritLabel = new JLabel(earned + " grit");
        gritLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        gritLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gritLabel.setFont(FontManager.getRunescapeSmallFont());
        gritLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        col.add(gritLabel);

        return col;
    }

    private static String wrapCentered(String text, int widthPx) {
        return "<html><div style='text-align:center;width:" + widthPx + "px'>" + text + "</div></html>";
    }

    private static String tierName(TrialType type) {
        switch (type) {
            case WEEKLY_EASY:
                return "Easy";
            case WEEKLY_MEDIUM:
                return "Medium";
            case WEEKLY_HARD:
                return "Hard";
            default:
                return "";
        }
    }

    /** Accent color per weekly difficulty so the three columns read apart at a glance. */
    private static Color tierColor(TrialType type) {
        switch (type) {
            case WEEKLY_EASY:
                return new Color(0x66, 0xcc, 0x66);
            case WEEKLY_MEDIUM:
                return new Color(0xe6, 0xc8, 0x4c);
            case WEEKLY_HARD:
                return new Color(0xe0, 0x7a, 0x3c);
            default:
                return ColorScheme.LIGHT_GRAY_COLOR;
        }
    }

    /**
     * Icon of the trial page's first (most representative) collection log
     * item, resolved through the game's own item sprites - no per-boss icon
     * list to maintain as new content ships.
     */
    private void loadIcon(String pageName, JLabel iconLabel) {
        Optional<ClogPage> page = clogData.getPage(pageName);
        if (!page.isPresent()) {
            return;
        }
        Iterator<Integer> items = page.get().getItemIds().iterator();
        if (!items.hasNext()) {
            return;
        }
        AsyncBufferedImage image = itemManager.getImage(items.next());
        image.onLoaded(() -> {
            BufferedImage scaled = ImageUtil.resizeImage(image, ICON_SIZE, ICON_SIZE);
            iconLabel.setIcon(new ImageIcon(scaled));
            iconLabel.repaint();
        });
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

    /** Static definition of one calendar period box: label and member slots. */
    private static final class PeriodDef {
        final String label;
        final TrialType[] types;

        PeriodDef(String label, TrialType... types) {
            this.label = label;
            this.types = types;
        }
    }
}
