package dev.jimmyyoung.printlab.input;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a folder and hands each new file matching {@code glob} to a callback — an input device.
 *
 * <p>Files can show up before they're fully written (a large copy over the network, say), so a file
 * is only handed off once its size has stopped changing. Files already present at startup are
 * picked up too.
 */
public final class HotFolderWatcher {

    private static final Logger log = LoggerFactory.getLogger(HotFolderWatcher.class);
    private static final long STABLE_CHECK_MILLIS = 500;

    private final Path folder;
    private final String glob;
    private final Consumer<Path> onFile;

    public HotFolderWatcher(Path folder, String glob, Consumer<Path> onFile) {
        this.folder = folder;
        this.glob = glob;
        this.onFile = onFile;
    }

    /** Blocks, watching the folder until the thread is interrupted. */
    public void watch() throws IOException, InterruptedException {
        Files.createDirectories(folder);
        var matcher = folder.getFileSystem().getPathMatcher("glob:" + glob);

        try (DirectoryStream<Path> existing = Files.newDirectoryStream(folder, glob)) {
            for (Path file : existing) {
                handOff(file);
            }
        }

        try (WatchService watcher = folder.getFileSystem().newWatchService()) {
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            log.info("Watching {} for {}", folder.toAbsolutePath(), glob);
            while (true) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path name = (Path) event.context();
                    if (matcher.matches(name)) {
                        handOff(folder.resolve(name));
                    }
                }
                if (!key.reset()) {
                    throw new IOException("Hot folder no longer accessible: " + folder);
                }
            }
        }
    }

    private void handOff(Path file) throws InterruptedException {
        try {
            waitUntilStable(file);
            onFile.accept(file);
        } catch (IOException e) {
            log.warn("Skipping {}: {}", file, e.getMessage());
        }
    }

    private static void waitUntilStable(Path file) throws IOException, InterruptedException {
        long previous = -1;
        long size = Files.size(file);
        while (size != previous) {
            Thread.sleep(STABLE_CHECK_MILLIS);
            previous = size;
            size = Files.size(file);
        }
    }
}
