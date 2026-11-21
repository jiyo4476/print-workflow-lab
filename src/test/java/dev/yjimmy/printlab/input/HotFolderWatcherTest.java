package dev.yjimmy.printlab.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HotFolderWatcherTest {

    private static final Duration FAST_STABLE_CHECK = Duration.ofMillis(10);
    private static final Duration FAST_RESCAN = Duration.ofMillis(100);
    private static final long WAIT_MILLIS = 10_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path hotFolder;
    private Path claimed;
    /** Names of the files the handler claimed, in order. */
    private final List<String> picked = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws IOException {
        hotFolder = tmp.getRoot().toPath().resolve("hotfolder");
        claimed = Files.createDirectories(tmp.getRoot().toPath().resolve("claimed"));
    }

    /** Claims by moving into {@code claimed/}, as the real handler does. */
    private void claim(Path file) throws IOException {
        Files.move(file, claimed.resolve(file.getFileName()));
        picked.add(file.getFileName().toString());
    }

    private HotFolderWatcher watcher(HotFolderWatcher.FileHandler handler) {
        return new HotFolderWatcher(hotFolder, "*.pdf", handler, FAST_STABLE_CHECK, FAST_RESCAN);
    }

    private Thread start(HotFolderWatcher watcher) {
        Thread thread = new Thread(() -> {
            try {
                watcher.watch();
            } catch (InterruptedException e) {
                // stopped by the test
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, "hot-folder-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stop(Thread thread) throws InterruptedException {
        thread.interrupt();
        thread.join(WAIT_MILLIS);
    }

    private void awaitPicked(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (picked.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(picked).as("files picked up").hasSizeGreaterThanOrEqualTo(count);
    }

    @Test
    public void picksUpFilesPresentAtStartupAndNewOnes() throws Exception {
        Files.createDirectories(hotFolder);
        Files.writeString(hotFolder.resolve("early.pdf"), "x");
        Thread thread = start(watcher(this::claim));
        try {
            awaitPicked(1);
            Files.writeString(hotFolder.resolve("late.pdf"), "y");
            awaitPicked(2);
        } finally {
            stop(thread);
        }

        assertThat(picked).containsExactly("early.pdf", "late.pdf");
        assertTrue(Files.exists(claimed.resolve("late.pdf")));
    }

    @Test
    public void globIgnoresCaseAndOtherFilesAreLeftAlone() throws Exception {
        Files.createDirectories(hotFolder);
        Files.writeString(hotFolder.resolve("RUN.PDF"), "x");
        Files.writeString(hotFolder.resolve("notes.txt"), "x");

        watcher(this::claim).scan();

        assertThat(picked).containsExactly("RUN.PDF");
        assertTrue("non-matching file stays", Files.exists(hotFolder.resolve("notes.txt")));
    }

    @Test
    public void aFileWhoseClaimFailedIsRetriedByTheRescan() throws Exception {
        Files.createDirectories(hotFolder);
        Files.writeString(hotFolder.resolve("locked.pdf"), "x");
        AtomicInteger attempts = new AtomicInteger();
        HotFolderWatcher watcher = watcher(file -> {
            if (attempts.incrementAndGet() <= 2) {
                // What Windows reports while another program still has the file open.
                throw new FileSystemException(file.toString(), null,
                        "The process cannot access the file because it is being used");
            }
            claim(file);
        });

        Thread thread = start(watcher);
        try {
            awaitPicked(1);
        } finally {
            stop(thread);
        }

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(picked).containsExactly("locked.pdf");
        assertThat(watcher.failing()).isEmpty();
    }

    @Test
    public void failingFilesAreTrackedUntilClaimed() throws Exception {
        Files.createDirectories(hotFolder);
        Path file = Files.writeString(hotFolder.resolve("locked.pdf"), "x");
        HotFolderWatcher watcher = watcher(f -> {
            throw new IOException("locked");
        });

        watcher.scan();
        watcher.scan();

        assertEquals(java.util.Set.of(file), watcher.failing());
        assertTrue("left in the hot folder", Files.exists(file));
        Files.delete(file);
        watcher.scan();
        assertThat(watcher.failing()).as("forgotten once gone").isEmpty();
    }

    @Test
    public void overflowRescansTheWholeFolder() throws Exception {
        Files.createDirectories(hotFolder);
        for (String name : List.of("a.pdf", "b.pdf", "c.pdf")) {
            Files.writeString(hotFolder.resolve(name), name);
        }
        HotFolderWatcher watcher = watcher(this::claim);

        // The watch service lost the events: all that arrives is OVERFLOW.
        watcher.handle(List.of(event(StandardWatchEventKinds.OVERFLOW, null)));

        assertThat(picked).containsExactly("a.pdf", "b.pdf", "c.pdf");
    }

    @Test
    public void createEventsHandOffOnlyMatchingFiles() throws Exception {
        Files.createDirectories(hotFolder);
        Files.writeString(hotFolder.resolve("a.pdf"), "a");
        Files.writeString(hotFolder.resolve("b.txt"), "b");
        HotFolderWatcher watcher = watcher(this::claim);

        watcher.handle(List.of(
                event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("a.pdf")),
                event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("b.txt"))));

        assertThat(picked).containsExactly("a.pdf");
    }

    @Test
    public void aFileAlreadyClaimedIsSkippedQuietly() throws Exception {
        Files.createDirectories(hotFolder);
        HotFolderWatcher watcher = watcher(this::claim);

        // An event for a file an earlier rescan already claimed.
        watcher.handle(List.of(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("gone.pdf"))));

        assertThat(picked).isEmpty();
        assertThat(watcher.failing()).isEmpty();
    }

    @Test
    public void jobTicketsAreNeverHandedOffEvenWhenTheGlobMatches() throws Exception {
        Files.createDirectories(hotFolder);
        Files.writeString(hotFolder.resolve("run.pdf.jobprops"), "customer=ACME");
        Files.writeString(hotFolder.resolve("RUN.JOBPROPS"), "customer=ACME");
        Files.writeString(hotFolder.resolve("run.pdf"), "pdf");
        HotFolderWatcher everything = new HotFolderWatcher(hotFolder, "*", this::claim,
                FAST_STABLE_CHECK, FAST_RESCAN);

        everything.scan();
        everything.handle(List.of(
                event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("RUN.JOBPROPS"))));

        assertThat(picked).containsExactly("run.pdf");
        assertEquals("a waiting ticket isn't reported as unmatched", 0,
                everything.unmatchedReports());
    }

    @Test
    public void unmatchedFileIsLoggedOnceWhileItStays() throws Exception {
        Files.createDirectories(hotFolder);
        Path notes = Files.writeString(hotFolder.resolve("notes.txt"), "x");
        HotFolderWatcher watcher = watcher(this::claim);

        watcher.scan();
        watcher.scan();
        watcher.handle(List.of(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("notes.txt"))));
        assertEquals("reported once while it stays", 1, watcher.unmatchedReports());

        Files.delete(notes);
        watcher.scan();
        Files.writeString(notes, "again");
        watcher.scan();
        assertEquals("reported again after it was removed and dropped again", 2,
                watcher.unmatchedReports());
    }

    private static <T> WatchEvent<T> event(WatchEvent.Kind<T> kind, T context) {
        return new WatchEvent<>() {
            @Override
            public Kind<T> kind() {
                return kind;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public T context() {
                return context;
            }
        };
    }
}
