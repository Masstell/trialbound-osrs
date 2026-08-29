package mvdicarlo.trialbound;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class TrialboundPluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(TrialboundPlugin.class);
        RuneLite.main(args);
    }
}
