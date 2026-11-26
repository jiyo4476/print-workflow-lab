package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.afp.AfpFraming;
import dev.yjimmy.printlab.afp.AfpReader;
import dev.yjimmy.printlab.afp.AfpWriter;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.afp.Tle;
import dev.yjimmy.printlab.afp.TleDecoder;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits an AFP print file into one document per named page group (BNG ... ENG) and turns the
 * group's TLEs into document properties. This is the AFP counterpart of {@link SplitPdfStep}:
 * AFP marks document boundaries and carries index values itself, so nothing is read off the page.
 *
 * <pre>
 *   params:
 *     requiredProperties: [accountNumber, name, zip]   # optional
 *     codePage: IBM037                                 # optional; TLE text, before any CGCSGID
 *     framing: strict                                  # optional: strict, padded, rdw, bdw, auto
 *     groupLevel: 1                                    # optional: how deep the document groups are
 * </pre>
 *
 * <p>{@code groupLevel} is the nesting depth of the page groups that are documents: 1 (the default)
 * is a group outside any other group. With 2, the groups at depth 1 are <em>wrappers</em> (a
 * household around its statements, say) and the groups inside them are the documents. Each
 * document is written inside its wrapper's Begin and TLEs, so it is still a standalone AFP file,
 * and the wrapper's TLEs are inherited as document properties, the document's own TLEs winning if a
 * name is in both. Other fields directly inside a wrapper (an IMM, a NOP) go to the documents after
 * them in the same wrapper and not to those in the next one, and a page directly inside a wrapper,
 * outside any document group, fails the job.
 *
 * <p>{@code framing} says what sits between the fields in the file (see {@link AfpFraming}):
 * nothing ({@code strict}, the default), carriage returns and line feeds with zero bytes only at
 * the end ({@code padded}), record descriptor words ({@code rdw}) or those inside blocks
 * ({@code bdw}), or {@code auto} to work it out from the start of the file. Each form still checks
 * every field, but {@code padded} and {@code auto} are looser than {@code strict} about what sits
 * between fields (see {@link AfpFraming}). Everything written is {@code strict}.
 *
 * <p>Each document is written as a standalone AFP file: every field before the first page group
 * (the BDT, and any inline resource group, BRG ... ERG), then the fields between the previous
 * group and this one (an IMM, say), then the group itself, then an End for each Begin still open
 * around it (the EDT), named like its Begin. Fields are copied as they are, except that padding is
 * dropped (see {@link AfpWriter#write(StructuredField)}).
 *
 * <p>An Invoke Medium Map (IMM) stays in effect until the next one, so a document with no IMM of
 * its own just before it gets a copy of the latest IMM after the preamble: one between earlier
 * groups, or between the pages of an earlier group. Without it, the document would print with
 * whatever medium map the preamble selects (another tray, duplex setting or form).
 *
 * <p>Fields after the last group and before the document's End (a trailing NOP, say) have no
 * following document to go into, so they're not copied anywhere.
 *
 * <p>Document properties are the TLEs directly inside the group, in file order, then
 * {@code pageCount} (BPGs anywhere in the group) and {@code format=afp}. TLEs inside pages or
 * nested groups describe those, not the document, and are ignored. A group missing a required
 * property (absent or blank) is marked with {@code indexError} instead of failing the job, and so
 * is one that repeats a TLE name with another value (the first value is kept).
 *
 * <p>A file may hold several documents one after another (BDT ... EDT, as when files are joined
 * with {@code cat}). Each is split on its own: its documents get that document's preamble and
 * medium map, and sequence numbers and page counts carry on through the file. A document with no
 * page groups (an empty trailer, say) is skipped.
 *
 * <p>The job fails, with the byte offset, for: bytes that aren't AFP, a Begin closed by the
 * wrong End or never closed, a page outside any page group, a TLE that can't be decoded, and,
 * once a document has ended (whether or not it had page groups), any field that doesn't begin
 * another document, a page group included. It also fails when the whole file has no page groups.
 *
 * <p>The file is read once, one field at a time, and each document is written as its group is
 * read; only the fields before the first group are kept in memory.
 */
public final class SplitAfpStep implements Step {

    private final List<String> requiredProperties;
    private final Charset codePage;
    private final AfpFraming framing;
    /** The nesting depth of the groups that are documents; 1 is a group outside any other. */
    private final int groupLevel;

    public SplitAfpStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.requiredProperties = params.optionalStringList("requiredProperties");
        this.framing = AfpFraming.named(params.string("framing", "strict"));
        this.groupLevel = params.integer("groupLevel", 1);
        if (groupLevel < 1) {
            throw new IllegalArgumentException("groupLevel must be 1 or more, not " + groupLevel);
        }
        String name = params.string("codePage", null);
        try {
            this.codePage = name == null ? Ebcdic.IBM037 : Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown 'codePage' '" + name + "'", e);
        }
    }

    @Override
    public void execute(Job job) throws IOException {
        Path docsDir = Files.createDirectories(job.workDir().resolve("documents"));
        try (InputStream in = Files.newInputStream(job.inputFile());
                AfpReader reader = new AfpReader(in, framing)) {
            new Split(job, docsDir).run(reader);
        }
        job.properties().put("documentCount", String.valueOf(job.documents().size()));
    }

    /** One pass over one file. */
    private final class Split {

        private final Job job;
        private final Path docsDir;
        /** Every Begin not yet closed, innermost first. */
        private final Deque<StructuredField> open = new ArrayDeque<>();
        /** Fields before the first group, copied into every document. */
        private final List<StructuredField> preamble = new ArrayList<>();
        /** Fields between the previous group and the next, copied into the next document. */
        private final List<StructuredField> between = new ArrayList<>();
        /**
         * The latest IMM after the preamble (between groups, or between an earlier group's
         * pages), or {@code null}: the medium map still in effect for the next document.
         */
        private StructuredField mediumMap;
        /** The groups around the document group, outermost first (see {@code groupLevel}). */
        private final List<Wrapper> wrappers = new ArrayList<>();
        private boolean seenGroup;
        /** A document ended (its EDT) and no other has begun: the next Begin starts a new one. */
        private boolean documentEnded;
        /** How many Begins were open outside the first group; fewer means the document ended. */
        private int documentDepth;
        private int totalPages;
        private Group group;

        Split(Job job, Path docsDir) {
            this.job = job;
            this.docsDir = docsDir;
        }

        void run(Iterator<StructuredField> fields) throws IOException {
            try {
                while (fields.hasNext()) {
                    accept(fields.next());
                }
            } finally {
                if (group != null) {
                    group.writer.close();
                }
            }
            if (!open.isEmpty()) {
                StructuredField begin = open.peek();
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "The %s at offset 0x%08X has no %s before the end of the file",
                        begin.typeName(), begin.offset(), FieldType.nameOf(begin.matchingEndId())));
            }
            if (job.documents().isEmpty()) {
                throw new IllegalStateException("The AFP file has no named page groups"
                        + (groupLevel > 1 ? " nested " + groupLevel + " deep (groupLevel: "
                        + groupLevel + ")" : "") + " (BNG ... ENG), so there are no documents to"
                        + " split out. SplitAfp needs one page group per document.");
            }
            job.properties().put("totalPages", String.valueOf(totalPages));
        }

        private void accept(StructuredField field) throws IOException {
            if (field.isEnd()) {
                close(field);
                if (field.type() == FieldType.EDT && open.isEmpty()) {
                    documentEnded = true;
                }
            }
            // True from a document's end until the next one begins. With page groups seen, that is
            // once everything around them (usually just the BDT) is closed; with none, once an EDT
            // closed everything.
            boolean ended = seenGroup ? documentDepth > 0 && open.isEmpty() : documentEnded;
            if (ended && field.type() == FieldType.BNG) {
                throw new IllegalStateException(at(field, "starts a page group after its document"
                        + " ended; another document must begin first (a BDT, say)"));
            }
            if (group != null) {
                group.add(field);
            } else if (field.type() == FieldType.BNG && bngsOpen() == groupLevel - 1) {
                startGroup(field);
            } else if (field.type() == FieldType.BNG) {
                wrappers.add(new Wrapper(field)); // fewer groups open than groupLevel - 1
            } else if (field.type() == FieldType.BPG) {
                throw new IllegalStateException(at(field, "is outside any named page group;"
                        + " SplitAfp needs every page in a page group (BNG ... ENG)"
                        + (wrappers.isEmpty() ? "" : ". It is directly inside a group that is only"
                        + " a wrapper because groupLevel is " + groupLevel + "; the file may not"
                        + " nest page groups that deep")));
            } else if (field.isBegin() && ended) {
                // The previous document has ended (one with page groups, or an empty one) and
                // another begins: its own preamble, medium map and depth. Page counts and
                // sequence numbers carry on.
                preamble.clear();
                between.clear();
                wrappers.clear();
                mediumMap = null;
                seenGroup = false;
                documentEnded = false;
                preamble.add(field);
            } else if (ended && !field.isEnd()) {
                // Whether or not the document had page groups: a document is followed by another
                // document, not by loose fields.
                throw new IllegalStateException(at(field, "comes after the end of the"
                        + " document and doesn't begin another (a BDT, say)"));
            } else if (!wrappers.isEmpty()) {
                inWrapper(field);
            } else if (!seenGroup) {
                preamble.add(field);
            } else if (open.size() < documentDepth && !field.isEnd()) {
                throw new IllegalStateException(at(field, "is outside the Begin ... End that"
                        + " holds the page groups before it; SplitAfp expects every page group of"
                        + " a document at the same depth"));
            } else if (open.size() >= documentDepth) {
                // Something between groups that applies to what follows (an IMM, a NOP, a
                // balanced Begin ... End). The Ends that close the document (open.size() now
                // below documentDepth) aren't copied: each document gets its own.
                between.add(field);
            }
            if (field.isBegin()) {
                open.push(field);
            }
            if (group != null && field.isEnd() && open.size() == group.depth) {
                finishGroup();
            }
        }

        /** Pops the Begin that {@code end} closes, or fails if it doesn't close the innermost. */
        private void close(StructuredField end) {
            if (open.isEmpty()) {
                throw new IllegalStateException(at(end, "has no matching Begin"));
            }
            StructuredField begin = open.peek();
            if (begin.matchingEndId() != end.typeId()) {
                throw new IllegalStateException(at(end, String.format(Locale.ROOT,
                        "doesn't close the %s at offset 0x%08X; expected %s first",
                        begin.typeName(), begin.offset(), FieldType.nameOf(begin.matchingEndId()))));
            }
            open.pop();
        }

        /** How many page groups are open now. */
        private int bngsOpen() {
            int n = 0;
            for (StructuredField begin : open) {
                if (begin.type() == FieldType.BNG) {
                    n++;
                }
            }
            return n;
        }

        /**
         * A field directly inside a wrapper group, outside any document group. The wrapper's TLEs
         * are kept to head each document under it and to be inherited; its end ends it, and what
         * else was inside it with it, so that nothing leaks into the documents of the next one.
         */
        private void inWrapper(StructuredField field) {
            Wrapper wrapper = wrappers.get(wrappers.size() - 1);
            if (field.type() == FieldType.ENG) {
                wrappers.remove(wrappers.size() - 1);
                between.clear();
            } else if (field.type() == FieldType.TLE && open.peek() == wrapper.begin) {
                wrapper.header.add(field);
                wrapper.addTle(field);
            } else {
                between.add(field);
            }
        }

        private Tle decode(StructuredField field) {
            try {
                return TleDecoder.decode(field, codePage);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(at(field, "can't be decoded: " + e.getMessage()),
                        e);
            }
        }

        private void startGroup(StructuredField bng) throws IOException {
            if (!seenGroup) {
                seenGroup = true;
                // The Begins around the groups, not counting the wrapper groups among them.
                documentDepth = open.size() - wrappers.size();
            }
            int sequence = job.documents().size() + 1;
            Path file = docsDir.resolve(String.format(Locale.ROOT, "doc-%05d.afp", sequence));
            group = new Group(sequence, file, open.size(), new AfpWriter(Files.newOutputStream(file)));
            for (StructuredField f : preamble) {
                group.writer.write(f);
            }
            for (Wrapper wrapper : wrappers) {
                group.writer.write(wrapper.begin);
                for (StructuredField f : wrapper.header) {
                    group.writer.write(f);
                }
                group.inherited.putAll(wrapper.tles);
                group.problems.addAll(wrapper.problems);
            }
            boolean ownMediumMap = between.stream().anyMatch(f -> f.type() == FieldType.IMM);
            if (!ownMediumMap && mediumMap != null) {
                group.writer.write(mediumMap);
            }
            for (StructuredField f : between) {
                group.writer.write(f);
                if (f.type() == FieldType.IMM) {
                    mediumMap = f;
                }
            }
            between.clear();
            group.writer.write(bng);
        }

        private void finishGroup() throws IOException {
            // Close whatever is still open around the group, innermost first: usually the BDT.
            for (StructuredField begin : open) {
                byte[] name = Arrays.copyOf(begin.data(), Math.min(begin.data().length,
                        AfpWriter.NAME_LENGTH));
                group.writer.write(begin.matchingEndId(), 0, name);
            }
            group.writer.close();
            // The wrappers' TLEs first, outermost first; the document's own win on a shared name.
            Map<String, String> props = new LinkedHashMap<>(group.inherited);
            props.putAll(group.properties);
            props.put("pageCount", String.valueOf(group.pages));
            props.put("format", "afp");
            IndexErrors.markMissing(props, requiredProperties);
            group.problems.forEach(problem -> IndexErrors.mark(props, problem));
            job.documents().add(new Document(group.sequence, group.file, props, group.sequence));
            totalPages += group.pages;
            group = null;
        }

        private String at(StructuredField field, String problem) {
            return String.format(Locale.ROOT, "The %s at offset 0x%08X %s",
                    field.typeName(), field.offset(), problem);
        }

        /** A group around the document groups: its Begin, its own TLEs, and what they say. */
        private final class Wrapper {
            final StructuredField begin;
            /** The TLEs directly inside it, as written, which head each document under it. */
            final List<StructuredField> header = new ArrayList<>();
            final Map<String, String> tles = new LinkedHashMap<>();
            final List<String> problems = new ArrayList<>();

            Wrapper(StructuredField begin) {
                this.begin = begin;
            }

            void addTle(StructuredField field) {
                Tle tle = decode(field);
                String earlier = tles.putIfAbsent(tle.name(), tle.value());
                if (earlier != null && !earlier.equals(tle.value())) {
                    problems.add("TLE '" + tle.name() + "' repeated with another value");
                }
            }
        }

        /** The group being written. {@code depth} is how many Begins are open around its BNG. */
        private final class Group {
            final int sequence;
            final Path file;
            final int depth;
            final AfpWriter writer;
            /** The TLEs of the groups around this one. */
            final Map<String, String> inherited = new LinkedHashMap<>();
            final Map<String, String> properties = new LinkedHashMap<>();
            final List<String> problems = new ArrayList<>();
            int pages;

            Group(int sequence, Path file, int depth, AfpWriter writer) {
                this.sequence = sequence;
                this.file = file;
                this.depth = depth;
                this.writer = writer;
            }

            /** Called before the field is pushed, so a TLE directly in the group sees just BNG. */
            void add(StructuredField field) throws IOException {
                writer.write(field);
                if (field.type() == FieldType.BPG) {
                    pages++;
                } else if (field.type() == FieldType.TLE && open.size() == depth + 1) {
                    addTle(field);
                } else if (field.type() == FieldType.IMM && open.peek().type() != FieldType.BPG) {
                    // Between this group's pages: it stays in effect for the next documents too.
                    mediumMap = field;
                }
            }

            private void addTle(StructuredField field) {
                Tle tle = decode(field);
                String earlier = properties.putIfAbsent(tle.name(), tle.value());
                if (earlier != null && !earlier.equals(tle.value())) {
                    problems.add("TLE '" + tle.name() + "' repeated with another value");
                }
            }
        }
    }
}
