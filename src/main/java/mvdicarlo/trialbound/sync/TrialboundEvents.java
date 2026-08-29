package mvdicarlo.trialbound.sync;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mvdicarlo.trialbound.store.TbEventRecord;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Live delta / reconciliation chunk broadcast to the party. Class simple name
 * is the global Gson type label - keep it unique across the plugin ecosystem.
 */
@Getter
@Setter
@NoArgsConstructor
public class TrialboundEvents extends PartyMemberMessage {
    private String fromPlayer;
    private List<TbEventRecord> events;
    private String hmac;

    public TrialboundEvents(String fromPlayer, List<TbEventRecord> events, String hmac) {
        this.fromPlayer = fromPlayer;
        this.events = events;
        this.hmac = hmac;
    }
}
