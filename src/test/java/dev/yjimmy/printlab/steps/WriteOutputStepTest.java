package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WriteOutputStepTest {

    private static final String NL = System.lineSeparator();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Path outputDir;
    private Job job;

    @Before
    public void setUp() throws Exception {
        tmp = folder.getRoot().toPath();
        outputDir = tmp.resolve("out");
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    @Test
    public void inputSequenceIsTheLastColumnAndKeepsTheInputPosition() throws Exception {
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.writeString(a, "a");
        Files.writeString(b, "b");
        job.documents().add(new Document(1, b, Map.of("zip", "10001"), 2));
        job.documents().add(new Document(2, a, Map.of("zip", "80202"), 1));

        write();

        assertEquals("csv", String.join(NL,
                "position,sequence,file,zip,inputSequence",
                "1,1,00001.pdf,10001,2",
                "2,2,00002.pdf,80202,1") + NL, csv());
    }

    @Test
    public void inputSequenceIsReserved() throws Exception {
        addDocument("a", Map.of("inputSequence", "7"));

        IllegalStateException e = assertThrows(IllegalStateException.class, this::write);

        assertThat(e.getMessage()).contains("inputSequence");
    }

    @Test
    public void copiesDocumentsInOrderAndWritesTheCsv() throws Exception {
        addDocument("a", Map.of("zip", "80202"));
        addDocument("b", Map.of("zip", "10001", "name", "AVERY"));

        write();

        Path jobOut = outputDir.resolve(job.id());
        assertEquals("first", "a", Files.readString(jobOut.resolve("00001.pdf")));
        assertEquals("second", "b", Files.readString(jobOut.resolve("00002.pdf")));
        assertEquals("csv", String.join(NL,
                "position,sequence,file,zip,name,inputSequence",
                "1,1,00001.pdf,80202,,1",
                "2,2,00002.pdf,10001,AVERY,2") + NL, csv());
        assertEquals("outputDir property", jobOut.toString(), job.properties().get("outputDir"));
    }

    @Test
    public void eachQueueIsNumberedFromOneInItsOwnFolder() throws Exception {
        addDocument("a", Map.of("queue", "mail"));
        addDocument("b", Map.of("queue", "exceptions"));
        addDocument("c", Map.of("queue", "mail"));
        addDocument("d", Map.of());

        write();

        Path jobOut = outputDir.resolve(job.id());
        assertEquals("mail 1", "a", Files.readString(jobOut.resolve("mail/00001.pdf")));
        assertEquals("exceptions 1", "b", Files.readString(jobOut.resolve("exceptions/00001.pdf")));
        assertEquals("mail 2", "c", Files.readString(jobOut.resolve("mail/00002.pdf")));
        assertEquals("no queue", "d", Files.readString(jobOut.resolve("00001.pdf")));
        assertThat(csv()).contains("2,3,mail/00002.pdf,mail").contains("1,4,00001.pdf,");
    }

    @Test
    public void csvQuotesCommasQuotesAndLineBreaks() throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("name", "NGUYEN, AVERY");
        props.put("note", "say \"hi\"");
        props.put("lf", "two\nlines");
        props.put("cr", "two\rlines");
        props.put("plain", "ok");
        props.put("city, state", "DENVER CO");
        addDocument("a", props);

        write();

        assertEquals("csv", String.join(NL,
                "position,sequence,file,name,note,lf,cr,plain,\"city, state\",inputSequence",
                "1,1,00001.pdf,\"NGUYEN, AVERY\",\"say \"\"hi\"\"\",\"two\nlines\","
                        + "\"two\rlines\",ok,DENVER CO,1") + NL, csv());
    }

    @Test
    public void unsafeQueueNameIsRejected() throws Exception {
        addDocument("a", Map.of("queue", "../escape"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::write);

        assertThat(e.getMessage()).contains("'../escape'");
        assertThat(tmp.resolve("escape")).doesNotExist();
    }

    @Test
    public void unsafeQueueNameOnALaterDocumentFailsBeforeAnythingIsCopied() throws Exception {
        addDocument("a", Map.of("queue", "mail"));
        addDocument("b", Map.of("queue", "../escape"));

        assertThrows(IllegalArgumentException.class, this::write);

        assertThat(outputDir.resolve(job.id())).doesNotExist();
    }

    @Test
    public void rerunReplacesEarlierOutputForTheSameJob() throws Exception {
        // An earlier, unfinished run of this job left files behind.
        Path jobOut = Files.createDirectories(outputDir.resolve(job.id()).resolve("mail"));
        Files.writeString(jobOut.resolve("00001.pdf"), "old");
        Files.writeString(jobOut.resolve("00002.pdf"), "stale");
        addDocument("a", Map.of("queue", "mail"));

        write();

        assertEquals("replaced", "a", Files.readString(jobOut.resolve("00001.pdf")));
        assertThat(jobOut.resolve("00002.pdf")).doesNotExist();
        assertThat(csv()).contains("1,1,mail/00001.pdf,mail");
    }

    @Test
    public void otherJobsOutputIsLeftAlone() throws Exception {
        Path other = Files.createDirectories(outputDir.resolve("another-job"));
        Files.writeString(other.resolve("00001.pdf"), "theirs");
        addDocument("a", Map.of());

        write();

        assertEquals("untouched", "theirs", Files.readString(other.resolve("00001.pdf")));
    }

    @Test
    public void noDocumentsWritesJustTheHeader() throws Exception {
        write();

        assertEquals("csv", "position,sequence,file,inputSequence" + NL, csv());
    }

    private void addDocument(String content, Map<String, String> properties) throws Exception {
        int sequence = job.documents().size() + 1;
        Path file = Files.writeString(tmp.resolve("doc-" + sequence + ".pdf"), content);
        job.documents().add(new Document(sequence, file, new LinkedHashMap<>(properties), sequence));
    }

    private void write() throws Exception {
        new WriteOutputStep(Map.of("outputDir", outputDir.toString())).execute(job);
    }

    private String csv() throws Exception {
        return Files.readString(outputDir.resolve(job.id()).resolve("documents.csv"));
    }

    @Test
    public void outputKeepsEachDocumentsExtension() throws Exception {
        Path afp = Files.writeString(tmp.resolve("doc-1.AFP"), "afp");
        job.documents().add(new Document(1, afp, new LinkedHashMap<>(Map.of("queue", "mail")), 1));
        addDocument("p", Map.of());

        write();

        Path jobOut = outputDir.resolve(job.id());
        assertEquals("afp", "afp", Files.readString(jobOut.resolve("mail/00001.afp")));
        assertEquals("pdf", "p", Files.readString(jobOut.resolve("00001.pdf")));
        assertThat(csv()).contains(",mail/00001.afp,").contains(",00001.pdf,");
    }

    @Test
    public void extensionIsLowercasedAndMayBeAbsent() {
        assertEquals(".afp", WriteOutputStep.extension(Path.of("x", "doc.AFP")));
        assertEquals("", WriteOutputStep.extension(Path.of("x", "doc")));
        assertEquals("a dot file has no extension", "",
                WriteOutputStep.extension(Path.of(".hidden")));
    }

    @Test
    public void aPropertyNamedLikeAReservedColumnFailsBeforeWritingAnything() throws Exception {
        addDocument("a", Map.of("zip", "80202"));
        addDocument("b", Map.of("file", "statement.pdf"));

        IllegalStateException e = assertThrows(IllegalStateException.class, this::write);

        assertThat(e.getMessage()).contains("Document 2").contains("'file'");
        assertThat(outputDir.resolve(job.id())).doesNotExist();
    }
}
