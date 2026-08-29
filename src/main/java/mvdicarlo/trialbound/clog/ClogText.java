package mvdicarlo.trialbound.clog;

import net.runelite.client.util.Text;

/**
 * Name normalization used for every page/NPC/item-name comparison so that all
 * group members resolve names identically (no fuzzy matching anywhere).
 */
public final class ClogText {
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = Text.removeTags(name)
                .replace('\u00A0', ' ')
                .toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastSpace = false;
            } else if (c == ' ' && !lastSpace && sb.length() > 0) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end);
    }

    private ClogText() {
    }
}
