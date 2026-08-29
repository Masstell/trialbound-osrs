package mvdicarlo.trialbound.trial;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import mvdicarlo.trialbound.clog.ClogText;

/**
 * Loads clog_boss_tiers.json: the curated easy/medium/hard/raids page lists,
 * NPC/event name aliases for drop attribution, and per-page grit/price
 * overrides. This file is the entire source-name matching strategy - fix
 * attribution gaps by editing it, never with fuzzy logic.
 */
@Slf4j
@Singleton
public class BossTierRegistry {
    private static final String RESOURCE = "/clog_boss_tiers.json";

    /** Normalized page name -> tier. */
    private final Map<String, TrialTier> tierByPage = new HashMap<>();
    /** Normalized alias -> raw page name. */
    private final Map<String, String> pageByAlias = new HashMap<>();
    /** Normalized page name -> entry (overrides, attribution). */
    private final Map<String, TierEntry> entryByPage = new HashMap<>();
    /** Tier -> raw page names sorted by name (deterministic selection pools). */
    private final Map<TrialTier, List<String>> pagesByTier = new HashMap<>();

    @Inject
    public BossTierRegistry(Gson gson) {
        TierFile file;
        try (InputStream in = BossTierRegistry.class.getResourceAsStream(RESOURCE)) {
            file = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), TierFile.class);
        } catch (Exception e) {
            log.error("Failed to load {}", RESOURCE, e);
            file = new TierFile();
        }
        register(TrialTier.EASY, file.tiers.get("easy"));
        register(TrialTier.MEDIUM, file.tiers.get("medium"));
        register(TrialTier.HARD, file.tiers.get("hard"));
        register(TrialTier.RAID, file.tiers.get("raids"));
        log.debug("Loaded boss tiers: {} pages, {} aliases", tierByPage.size(), pageByAlias.size());
    }

    private void register(TrialTier tier, List<TierEntry> entries) {
        List<String> pages = new ArrayList<>();
        if (entries != null) {
            for (TierEntry entry : entries) {
                if (entry == null || entry.page == null || entry.page.isEmpty()) {
                    continue;
                }
                String normalized = ClogText.normalize(entry.page);
                tierByPage.put(normalized, tier);
                entryByPage.put(normalized, entry);
                pages.add(entry.page);
                pageByAlias.put(normalized, entry.page);
                if (entry.aliases != null) {
                    for (String alias : entry.aliases) {
                        pageByAlias.put(ClogText.normalize(alias), entry.page);
                    }
                }
            }
        }
        Collections.sort(pages);
        pagesByTier.put(tier, Collections.unmodifiableList(pages));
    }

    /** Tier of a page, NON_BOSS when the page is not in the tier file. */
    public TrialTier getTier(String pageName) {
        return tierByPage.getOrDefault(ClogText.normalize(pageName), TrialTier.NON_BOSS);
    }

    /** Resolves an NPC/event source name to a raw page name via the alias table. */
    public Optional<String> resolveAlias(String sourceName) {
        return Optional.ofNullable(pageByAlias.get(ClogText.normalize(sourceName)));
    }

    public OptionalInt getGritBaseOverride(String pageName) {
        TierEntry entry = entryByPage.get(ClogText.normalize(pageName));
        return entry != null && entry.gritBase != null ? OptionalInt.of(entry.gritBase) : OptionalInt.empty();
    }

    public OptionalInt getPriceOverride(String pageName) {
        TierEntry entry = entryByPage.get(ClogText.normalize(pageName));
        return entry != null && entry.price != null ? OptionalInt.of(entry.price) : OptionalInt.empty();
    }

    /** Attribution kind for a page: "npc" (default), "widget" or "event". */
    public String getAttribution(String pageName) {
        TierEntry entry = entryByPage.get(ClogText.normalize(pageName));
        return entry != null && entry.attribution != null ? entry.attribution : "npc";
    }

    /** Raw page names in a tier, sorted by name. */
    public List<String> getPages(TrialTier tier) {
        return pagesByTier.getOrDefault(tier, Collections.emptyList());
    }

    /** All raw page names that have "event" attribution (Loot Tracker dependent). */
    public List<String> getEventAttributedPages() {
        List<String> pages = new ArrayList<>();
        for (TierEntry entry : entryByPage.values()) {
            if ("event".equals(entry.attribution)) {
                pages.add(entry.page);
            }
        }
        Collections.sort(pages);
        return pages;
    }

    /** Logs tier-file page names that do not exist in the cache-derived page set. */
    public void validateAgainstPages(java.util.function.Predicate<String> pageExists) {
        for (TierEntry entry : entryByPage.values()) {
            if (!pageExists.test(entry.page)) {
                log.warn("clog_boss_tiers.json page '{}' does not match any collection log page in the game cache",
                        entry.page);
            }
        }
    }

    private static class TierFile {
        int version;
        Map<String, List<TierEntry>> tiers = new HashMap<>();
    }

    @Value
    static class TierEntry {
        String page;
        List<String> aliases;
        String attribution;
        Integer gritBase;
        Integer price;
    }
}
