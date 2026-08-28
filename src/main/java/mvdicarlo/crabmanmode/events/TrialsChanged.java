package mvdicarlo.crabmanmode.events;

import java.util.List;

import lombok.Value;
import mvdicarlo.crabmanmode.trial.TrialSlot;

/** Posted on the client thread when the active trials are (re)computed. */
@Value
public class TrialsChanged {
    List<TrialSlot> trials;
}
