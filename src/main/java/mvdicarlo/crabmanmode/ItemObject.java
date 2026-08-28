package mvdicarlo.crabmanmode;

import java.time.Instant;
import net.runelite.client.util.AsyncBufferedImage;

public class ItemObject {
    int id;
    String name;
    boolean tradeable;
    Instant acquiredOn;
    AsyncBufferedImage icon;

    public ItemObject(int id, String name, boolean tradeable, Instant acquiredOn, AsyncBufferedImage icon) {
        this.id = id;
        this.name = name;
        this.tradeable = tradeable;
        this.acquiredOn = acquiredOn;
        this.icon = icon;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isTradeable() {
        return tradeable;
    }

    public Instant getAcquiredOn() {
        return acquiredOn;
    }

    public AsyncBufferedImage getIcon() {
        return icon;
    }
}
