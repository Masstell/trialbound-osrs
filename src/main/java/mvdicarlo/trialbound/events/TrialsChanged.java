package mvdicarlo.trialbound.events;

import java.util.List;

import lombok.Value;
import mvdicarlo.trialbound.trial.TrialSlot;

/** Posted on the client thread when the active trials are (re)computed. */
@Value
public class TrialsChanged {
    List<TrialSlot> trials;
}
