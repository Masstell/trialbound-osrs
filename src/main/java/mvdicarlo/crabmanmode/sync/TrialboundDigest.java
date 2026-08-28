package mvdicarlo.crabmanmode.sync;

import java.util.Map;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Per-month event-set digest ("yyyy-MM" -> "count:hexhash") sent on party
 * join/UserSync; peers reply with their events from any differing shard.
 */
@Getter
@Setter
@NoArgsConstructor
public class TrialboundDigest extends PartyMemberMessage {
    private String fromPlayer;
    private Map<String, String> shards;
    private String hmac;

    public TrialboundDigest(String fromPlayer, Map<String, String> shards, String hmac) {
        this.fromPlayer = fromPlayer;
        this.shards = shards;
        this.hmac = hmac;
    }
}
