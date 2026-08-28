package mvdicarlo.crabmanmode.sync;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import java.util.HashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import mvdicarlo.crabmanmode.CrabmanModeConfig;
import mvdicarlo.crabmanmode.SessionState;
import mvdicarlo.crabmanmode.TrialboundChat;
import mvdicarlo.crabmanmode.TrialboundVersion;
import mvdicarlo.crabmanmode.store.GroupStateListener;
import mvdicarlo.crabmanmode.store.GroupStateService;
import mvdicarlo.crabmanmode.store.TbEventRecord;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.util.Text;

/**
 * Party-websocket transport: broadcasts locally-originated events live and
 * reconciles full state whenever peers meet (digest exchange on UserSync).
 * The party server is a stateless relay - offline members converge
 * transitively when they are next online with anyone carrying the state.
 */
@Slf4j
@Singleton
public class PartySyncService {
    /** Max events per party message; server payload limits are unknown, stay modest. */
    private static final int CHUNK_SIZE = 50;
    private static final DateTimeFormatter SHARD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")
            .withZone(ZoneOffset.UTC);

    private final PartyService partyService;
    private final WSClient wsClient;
    private final ClientThread clientThread;
    private final CrabmanModeConfig config;
    private final GroupStateService groupState;
    private final SessionState sessionState;
    private final Gson gson;
    private final TrialboundChat chat;

    /** Players already warned about a version mismatch this session. */
    private final Set<String> versionWarned = new HashSet<>();

    /** Outbound event queue, drained in throttled chunks on game ticks. */
    private final Deque<TbEventRecord> outbound = new ArrayDeque<>();
    private int ticksUntilSend;
    private int ticksUntilDigest;
    private boolean digestRequested;
    private boolean started;

    private final GroupStateListener broadcastListener = new GroupStateListener() {
        @Override
        public void onEventsApplied(List<TbEventRecord> events, boolean remoteOrigin) {
            if (remoteOrigin) {
                return;
            }
            synchronized (outbound) {
                outbound.addAll(events);
            }
        }
    };

    @Inject
    public PartySyncService(PartyService partyService, WSClient wsClient, ClientThread clientThread,
            CrabmanModeConfig config, GroupStateService groupState, SessionState sessionState, Gson gson,
            TrialboundChat chat) {
        this.partyService = partyService;
        this.wsClient = wsClient;
        this.clientThread = clientThread;
        this.config = config;
        this.groupState = groupState;
        this.sessionState = sessionState;
        this.gson = gson;
        this.chat = chat;
    }

    public void startUp() {
        if (started) {
            return;
        }
        started = true;
        wsClient.registerMessage(TrialboundEvents.class);
        wsClient.registerMessage(TrialboundDigest.class);
        groupState.addListener(broadcastListener);
        maybeJoinParty();
    }

    public void shutDown() {
        if (!started) {
            return;
        }
        started = false;
        groupState.removeListener(broadcastListener);
        wsClient.unregisterMessage(TrialboundEvents.class);
        wsClient.unregisterMessage(TrialboundDigest.class);
        // Leave only a party we own (the configured Trialbound passphrase).
        String passphrase = config.partyPassphrase().trim();
        if (!passphrase.isEmpty() && passphrase.equals(partyService.getPartyPassphrase())) {
            partyService.changeParty(null);
        }
        synchronized (outbound) {
            outbound.clear();
        }
    }

    /** Joins the configured Trialbound party if not already in it. */
    public void maybeJoinParty() {
        String passphrase = config.partyPassphrase().trim();
        if (passphrase.isEmpty()) {
            return;
        }
        clientThread.invokeLater(() -> {
            if (!passphrase.equals(partyService.getPartyPassphrase())) {
                log.info("Joining Trialbound party");
                partyService.changeParty(passphrase);
                requestDigestBroadcast();
            }
        });
    }

    // --- inbound ---

    @Subscribe
    public void onUserSync(UserSync event) {
        // A member (possibly us) joined and asked for state: everyone shares
        // their digest so differing shards get exchanged.
        requestDigestBroadcast();
    }

    @Subscribe
    public void onTrialboundEvents(TrialboundEvents message) {
        if (isOwn(message.getMemberId()) || message.getEvents() == null) {
            return;
        }
        if (!PartyAuth.verify(config.groupPassword(), eventsPayload(message.getFromPlayer(), message.getEvents()),
                message.getHmac())) {
            log.warn("Rejected Trialbound events from '{}' - bad group password", message.getFromPlayer());
            return;
        }
        int applied = groupState.mergeRemote(message.getEvents());
        if (applied > 0) {
            log.info("Merged {} events from {}", applied, message.getFromPlayer());
        }
    }

    @Subscribe
    public void onTrialboundDigest(TrialboundDigest message) {
        if (isOwn(message.getMemberId()) || message.getShards() == null) {
            return;
        }
        if (!PartyAuth.verify(config.groupPassword(), digestPayload(message.getFromPlayer(), message.getShards()),
                message.getHmac())) {
            log.warn("Rejected Trialbound digest from '{}' - bad group password", message.getFromPlayer());
            return;
        }
        String theirs = message.getVersion() == null ? "pre-0.2.0" : message.getVersion();
        if (!TrialboundVersion.VERSION.equals(theirs) && versionWarned.add(message.getFromPlayer())) {
            chat.send("Version mismatch: " + message.getFromPlayer() + " runs Trialbound v" + theirs
                    + ", you run v" + TrialboundVersion.VERSION + " - update to the same build!");
            log.warn("Version mismatch: {} on {}, local {}", message.getFromPlayer(), theirs,
                    TrialboundVersion.VERSION);
        }
        Map<String, String> mine = computeShards();
        List<TbEventRecord> toSend = new ArrayList<>();
        for (Map.Entry<String, String> entry : mine.entrySet()) {
            String shard = entry.getKey();
            if (!entry.getValue().equals(message.getShards().get(shard))) {
                toSend.addAll(eventsInShard(shard));
            }
        }
        if (!toSend.isEmpty()) {
            log.debug("Digest from {} differs; queueing {} events", message.getFromPlayer(), toSend.size());
            synchronized (outbound) {
                outbound.addAll(toSend);
            }
        }
    }

    // --- outbound (throttled) ---

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (!partyService.isInParty()) {
            return;
        }
        if (digestRequested && --ticksUntilDigest <= 0) {
            digestRequested = false;
            sendDigest();
        }
        if (--ticksUntilSend > 0) {
            return;
        }
        List<TbEventRecord> chunk = null;
        synchronized (outbound) {
            if (!outbound.isEmpty()) {
                chunk = new ArrayList<>(Math.min(CHUNK_SIZE, outbound.size()));
                while (chunk.size() < CHUNK_SIZE && !outbound.isEmpty()) {
                    chunk.add(outbound.poll());
                }
            }
        }
        if (chunk != null) {
            String player = localPlayerName();
            partyService.send(new TrialboundEvents(player, chunk,
                    PartyAuth.hmac(config.groupPassword(), eventsPayload(player, chunk))));
        }
        // Ecosystem convention: back off as the party grows.
        ticksUntilSend = Math.max(1, partyService.getMembers().size() - 6);
    }

    private void requestDigestBroadcast() {
        digestRequested = true;
        // Small delay spreads the burst when several members join at once.
        ticksUntilDigest = 2;
    }

    private void sendDigest() {
        if (!partyService.isInParty() || !groupState.isReady()) {
            return;
        }
        Map<String, String> shards = computeShards();
        String player = localPlayerName();
        partyService.send(new TrialboundDigest(player, shards,
                PartyAuth.hmac(config.groupPassword(), digestPayload(player, shards)),
                TrialboundVersion.VERSION));
    }

    // --- helpers ---

    private boolean isOwn(long memberId) {
        return partyService.getLocalMember() != null && partyService.getLocalMember().getMemberId() == memberId;
    }

    private String localPlayerName() {
        String name = sessionState.getCurrentCharacter();
        return name.isEmpty() ? "unknown" : Text.sanitize(name);
    }

    private Map<String, String> computeShards() {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> hashes = new TreeMap<>();
        for (TbEventRecord event : groupState.getAllEvents()) {
            String shard = shardOf(event);
            counts.merge(shard, 1, Integer::sum);
            hashes.merge(shard, event.getId().hashCode(), (a, b) -> a ^ b);
        }
        Map<String, String> shards = new TreeMap<>();
        for (String shard : counts.keySet()) {
            shards.put(shard, counts.get(shard) + ":" + Integer.toHexString(hashes.get(shard)));
        }
        return shards;
    }

    private List<TbEventRecord> eventsInShard(String shard) {
        List<TbEventRecord> result = new ArrayList<>();
        for (TbEventRecord event : groupState.getAllEvents()) {
            if (shardOf(event).equals(shard)) {
                result.add(event);
            }
        }
        return result;
    }

    private static String shardOf(TbEventRecord event) {
        return SHARD_FORMAT.format(Instant.ofEpochMilli(event.getCreatedOn()));
    }

    private String eventsPayload(String fromPlayer, List<TbEventRecord> events) {
        return fromPlayer + "|" + gson.toJson(events);
    }

    private String digestPayload(String fromPlayer, Map<String, String> shards) {
        StringBuilder sb = new StringBuilder(fromPlayer).append('|');
        new TreeMap<>(shards).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }
}
