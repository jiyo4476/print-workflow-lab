package dev.yjimmy.printlab.steps;

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
 * </pre>
 *
 * <p>Each document is written as a standalone AFP file: every field before the first page group
 * (the BDT, and any inline resource group, BRG ... ERG), then the fields between the previous
 * group and this one (an IMM, say), then the group itself, then an End for each Begin still open
 * around it (the EDT), named like its Begin. Fields are copied as they are, except that padding is
 * dropped (see {@link AfpWriter#write(StructuredField)}).
 *
 * <p>Document properties are the TLEs directly inside the group, in file order, then
 * {@code pageCount} (BPGs anywhere in the group) and {@code format=afp}. TLEs inside pages or
 * nested groups describe those, not the document, and are ignored. A group missing a required
 * property (absent or blank) is marked with {@code indexError} instead of failing the job, and so
 * is one that repeats a TLE name with another value (the first value is kept).
 *
 * <p>The job fails, with the byte offset, for: bytes that aren't AFP, a Begin closed by the
 * wrong End or never closed, a page outside any page group, a TLE that can't be decoded, and
 * content after the file's document has ended. It also fails when there are no page groups.
 *
 * <p>The file is read once, one field at a time, and each document is written as its group is
 * read; only the fields before the first group are kept in memory.
 */
public final class SplitAfpStep implements Step {

    private final List<String> requiredProperties;
    private final Charset codePage;

    public SplitAfpStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.requiredProperties = params.optionalStringList("requiredProperties");
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
                AfpReader reader = new AfpReader(in)) {
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
        private boolean seenGroup;
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
            if (!seenGroup) {
                throw new IllegalStateException("The AFP file has no named page groups"
                        + " (BNG ... ENG), so there are no documents to split out. SplitAfp"
                        + " needs one page group per document.");
            }
            job.properties().put("totalPages", String.valueOf(totalPages));
        }

        private void accept(StructuredField field) throws IOException {
            if (field.isEnd()) {
                close(field);
            }
            if (group != null) {
                group.add(field);
            } else if (field.type() == FieldType.BNG) {
                startGroup(field);
            } else if (field.type() == FieldType.BPG) {
                throw new IllegalStateException(at(field, "is outside any named page group;"
                        + " SplitAfp needs every page in a page group (BNG ... ENG)"));
            } else if (!seenGroup) {
                preamble.add(field);
            } else if (open.size() < documentDepth && !field.isEnd()) {
                throw new IllegalStateException(at(field, "comes after the end of the"
                        + " document; SplitAfp handles one document (BDT ... EDT) per file"));
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

        private void startGroup(StructuredField bng) throws IOException {
            if (!seenGroup) {
                seenGroup = true;
                documentDepth = open.size();
            }
            int sequence = job.documents().size() + 1;
            Path file = docsDir.resolve(String.format(Locale.ROOT, "doc-%05d.afp", sequence));
            group = new Group(sequence, file, open.size(), new AfpWriter(Files.newOutputStream(file)));
            for (StructuredField f : preamble) {
                group.writer.write(f);
            }
            for (StructuredField f : between) {
                group.writer.write(f);
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
            Map<String, String> props = group.properties;
            props.put("pageCount", String.valueOf(group.pages));
            props.put("format", "afp");
            IndexErrors.markMissing(props, requiredProperties);
            group.problems.forEach(problem -> IndexErrors.mark(props, problem));
            job.documents().add(new Document(group.sequence, group.file, props));
            totalPages += group.pages;
            group = null;
        }

        private String at(StructuredField field, String problem) {
            return String.format(Locale.ROOT, "The %s at offset 0x%08X %s",
                    field.typeName(), field.offset(), problem);
        }

        /** The group being written. {@code depth} is how many Begins are open around its BNG. */
        private final class Group {
            final int sequence;
            final Path file;
            final int depth;
            final AfpWriter writer;
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
                }
            }

            private void addTle(StructuredField field) {
                Tle tle;
                try {
                    tle = TleDecoder.decode(field, codePage);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(at(field, "can't be decoded: "
                            + e.getMessage()), e);
                }
                String earlier = properties.putIfAbsent(tle.name(), tle.value());
                if (earlier != null && !earlier.equals(tle.value())) {
                    problems.add("TLE '" + tle.name() + "' repeated with another value");
                }
            }
        }
    }
}
