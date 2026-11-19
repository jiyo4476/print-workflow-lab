package dev.yjimmy.printlab.input;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a folder and hands each new file matching {@code glob} to a handler: an input device.
 *
 * <p>The handler must <b>claim</b> the file before it returns, by moving it out of the folder. A
 * file still in the folder is therefore one nobody has claimed, and handing it off again is safe.
 * That makes two safety nets possible:
 * <ul>
 *   <li>When the watch service drops events ({@code OVERFLOW}, e.g. a burst of files), the
 *       folder is rescanned at once.</li>
 *   <li>The folder is also rescanned every {@code rescanInterval}, so a file whose handler failed
 *       (still locked by the program copying it, say) is retried rather than left until the next
 *       restart.</li>
 * </ul>
 * Files can show up before they're fully written (a large copy over the network, say), so a file
 * is only handed off once its size has stopped changing. Files already present at startup are
 * picked up too. The glob ignores case ({@code *.pdf} matches {@code RUN.PDF}) on every platform.
 */
public final class HotFolderWatcher {

    private static final Logger log = LoggerFactory.getLogger(HotFolderWatcher.class);

    /** What happens to a file once it's complete; it must move the file out of the folder. */
    @FunctionalInterface
    public interface FileHandler {
        /** @throws IOException if the file couldn't be claimed; it will be retried */
        void accept(Path file) throws IOException;
    }

    static final Duration STABLE_CHECK = Duration.ofMillis(500);
    static final Duration RESCAN_INTERVAL = Duration.ofSeconds(5);

    private final Path folder;
    private final PathMatcher matcher;
    private final String glob;
    private final FileHandler onFile;
    private final Duration stableCheck;
    private final Duration rescanInterval;
    /** Files whose handler has failed and that are still waiting to be claimed. */
    private final Set<Path> failing = new HashSet<>();

    public HotFolderWatcher(Path folder, String glob, FileHandler onFile) {
        this(folder, glob, onFile, STABLE_CHECK, RESCAN_INTERVAL);
    }

    HotFolderWatcher(Path folder, String glob, FileHandler onFile, Duration stableCheck,
            Duration rescanInterval) {
        this.folder = folder;
        this.glob = glob;
        // Match on lowercase names: Windows ignores case in globs and Linux doesn't.
        this.matcher = folder.getFileSystem().getPathMatcher(
                "glob:" + glob.toLowerCase(Locale.ROOT));
        this.onFile = onFile;
        this.stableCheck = stableCheck;
        this.rescanInterval = rescanInterval;
    }

    /** Blocks, watching the folder until the thread is interrupted. */
    public void watch() throws IOException, InterruptedException {
        Files.createDirectories(folder);
        try (WatchService watcher = folder.getFileSystem().newWatchService()) {
            // Register before the first scan, so a file arriving between the two isn't missed.
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            log.info("Watching {} for {}", folder.toAbsolutePath(), glob);
            scan();
            while (true) {
                WatchKey key = watcher.poll(rescanInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (key == null) {
                    scan();
                    continue;
                }
                handle(key.pollEvents());
                if (!key.reset()) {
                    throw new IOException("Hot folder no longer accessible: " + folder);
                }
            }
        }
    }

    /** Hands off the files these events name, or rescans the folder if events were lost. */
    void handle(List<WatchEvent<?>> events) throws IOException, InterruptedException {
        List<Path> created = new ArrayList<>();
        boolean overflow = false;
        for (WatchEvent<?> event : events) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                overflow = true;
            } else if (event.context() instanceof Path name) {
                created.add(folder.resolve(name));
            }
        }
        if (overflow) {
            log.warn("Events for {} were lost (overflow); rescanning it", folder);
            scan();
            return;
        }
        for (Path file : created) {
            if (matches(file)) {
                handOff(file);
            }
        }
    }

    /** Hands off every matching file in the folder, in name order: unclaimed by definition. */
    void scan() throws IOException, InterruptedException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path file : stream) {
                if (matches(file) && Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        files.sort(null);
        failing.retainAll(files);
        for (Path file : files) {
            handOff(file);
        }
    }

    private boolean matches(Path file) {
        return matcher.matches(Path.of(file.getFileName().toString().toLowerCase(Locale.ROOT)));
    }

    private void handOff(Path file) throws InterruptedException {
        try {
            waitUntilStable(file);
            onFile.accept(file);
            if (failing.remove(file)) {
                log.info("Picked up {} after an earlier failure", file);
            }
        } catch (NoSuchFileException e) {
            // Gone already: claimed by an earlier hand-off, or deleted. Nothing to do.
            failing.remove(file);
        } catch (IOException | RuntimeException e) {
            // Warn once per file; the periodic rescan keeps retrying it quietly.
            if (failing.add(file)) {
                log.warn("Could not pick up {}; will retry every {}s: {}", file,
                        rescanInterval.toSeconds(), e.toString());
            } else {
                log.debug("Still could not pick up {}: {}", file, e.toString());
            }
        }
    }

    private void waitUntilStable(Path file) throws IOException, InterruptedException {
        long previous = -1;
        long size = Files.size(file);
        while (size != previous) {
            Thread.sleep(stableCheck.toMillis());
            previous = size;
            size = Files.size(file);
        }
    }

    /** Files whose hand-off has failed and that are waiting for a retry. */
    Set<Path> failing() {
        return Set.copyOf(failing);
    }
}
