package mvdicarlo.trialbound.store;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Durable append-only JSONL event log under ~/.runelite/trialbound/&lt;groupKey&gt;/.
 * The local file is the source of truth for group state; transports only ever
 * add events. Duplicate ids across lines are resolved at load time by the
 * deterministic winner order.
 */
@Slf4j
@Singleton
public class TbEventStore {
    private final Gson gson;
    private final File baseDir;

    @Inject
    public TbEventStore(Gson gson) {
        this(gson, new File(RuneLite.RUNELITE_DIR, "trialbound"));
    }

    /** Test seam: store rooted at an arbitrary directory. */
    public TbEventStore(Gson gson, File baseDir) {
        this.gson = gson;
        this.baseDir = baseDir;
    }

    private File fileFor(String groupKey) {
        return new File(new File(baseDir, groupKey), "events.jsonl");
    }

    public synchronized List<TbEventRecord> load(String groupKey) {
        File file = fileFor(groupKey);
        List<TbEventRecord> events = new ArrayList<>();
        if (!file.exists()) {
            return events;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    TbEventRecord event = gson.fromJson(line, TbEventRecord.class);
                    if (event != null && event.isValid()) {
                        events.add(event);
                    } else {
                        log.warn("Skipping invalid event line in {}", file);
                    }
                } catch (RuntimeException e) {
                    log.warn("Skipping corrupt event line in {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read event store {}", file, e);
        }
        log.debug("Loaded {} events from {}", events.size(), file);
        return events;
    }

    public synchronized void append(String groupKey, Collection<TbEventRecord> events) {
        if (events.isEmpty()) {
            return;
        }
        File file = fileFor(groupKey);
        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Cannot create event store directory {}", dir);
            return;
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            for (TbEventRecord event : events) {
                writer.write(gson.toJson(event));
                writer.write('\n');
            }
        } catch (IOException e) {
            log.error("Failed to append {} events to {}", events.size(), file, e);
        }
    }
}
