package mvdicarlo.trialbound.store;

import java.util.List;

/**
 * Notifications fire on whichever thread applied the events (client thread for
 * local intents, a transport thread for merges) - consumers touching game or
 * Swing state must marshal themselves.
 */
public interface GroupStateListener {
    /** Items that just became unlocked (their surviving unlock event). */
    default void onUnlocksAdded(List<TbEventRecord> unlocks) {
    }

    /** Items that just became locked again (relock tombstones). */
    default void onUnlocksRemoved(List<Integer> itemIds) {
    }

    /** Any grit balance changed. */
    default void onGritChanged() {
    }

    /**
     * Every event that was newly applied to the store. remoteOrigin is true
     * when it arrived from a transport (do not re-broadcast those).
     */
    default void onEventsApplied(List<TbEventRecord> events, boolean remoteOrigin) {
    }
}
