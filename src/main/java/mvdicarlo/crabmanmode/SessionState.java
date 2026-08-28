package mvdicarlo.crabmanmode;

import javax.inject.Singleton;

import lombok.Getter;
import lombok.Setter;

/**
 * Single gate for "should Trialbound do anything right now": the configured
 * character is logged in and we are not on a seasonal world. Mutated only on
 * the client thread; read from anywhere.
 */
@Singleton
public class SessionState {
    /** Character name Trialbound is enabled for (from config, trimmed). */
    @Getter
    private volatile String enabledCharacter = "";

    /** Currently logged-in character name, or empty. */
    @Getter
    private volatile String currentCharacter = "";

    @Getter
    @Setter
    private volatile boolean seasonalWorld;

    public void setEnabledCharacter(String name) {
        enabledCharacter = normalize(name);
    }

    public void setCurrentCharacter(String name) {
        currentCharacter = normalize(name);
    }

    /** True when the logged-in character is the configured Trialbound character. */
    public boolean isActiveCharacter() {
        String enabled = enabledCharacter;
        return !enabled.isEmpty() && enabled.equalsIgnoreCase(currentCharacter);
    }

    /** True when tracking/enforcement should run at all. */
    public boolean isActive() {
        return isActiveCharacter() && !seasonalWorld;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replace('\u00A0', ' ').trim();
    }
}
