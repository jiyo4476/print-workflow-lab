package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.afp.AfpFormatException;
import dev.yjimmy.printlab.afp.AfpReader;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.testsupport.AfpBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SplitAfpStepTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void oneDocumentPerPageGroup() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        group(afp, "G1", 1, "accountNumber", "111");
        group(afp, "G2", 2, "accountNumber", "222");
        group(afp, "G3", 1, "accountNumber", "333");
        afp.end(FieldType.EDT);

        Job job = split(afp, Map.of());

        assertThat(job.documents()).extracting(Document::sequence).containsExactly(1, 2, 3);
        assertThat(job.documents()).extracting(d -> d.file().getFileName().toString())
                .containsExactly("doc-00001.afp", "doc-00002.afp", "doc-00003.afp");
        assertEquals("documentCount", "3", job.properties().get("documentCount"));
        assertEquals("totalPages", "4", job.properties().get("totalPages"));
        // Each document is the BDT, its own group, and an EDT.
        assertThat(types(job.documents().get(1).file())).containsExactly(
                FieldType.BDT, FieldType.BNG, FieldType.TLE, FieldType.BPG, FieldType.EPG,
                FieldType.BPG, FieldType.EPG, FieldType.ENG, FieldType.EDT);
    }

    @Test
    public void tlesBecomeDocumentProperties() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")
                .begin(FieldType.BNG, "G1")
                .tle("accountNumber", "1234567890")
                .tle("name", "AVERY JONES")
                .tle("zip", "80202")
                .begin(FieldType.BPG, "P1")
                // A TLE inside a page describes the page, not the document.
                .tle("pageTag", "ignored")
                .end(FieldType.EPG)
                .end(FieldType.ENG)
                .end(FieldType.EDT);

        Job job = split(afp, Map.of());

        assertEquals("properties, in file order", List.of(
                Map.entry("accountNumber", "1234567890"), Map.entry("name", "AVERY JONES"),
                Map.entry("zip", "80202"), Map.entry("pageCount", "1"), Map.entry("format", "afp")),
                List.copyOf(job.documents().get(0).properties().entrySet()));
    }

    @Test
    public void pageCountCountsBpgWithinGroup() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        group(afp, "G1", 3, "accountNumber", "111");
        group(afp, "G2", 1, "accountNumber", "222");
        // A nested group's pages belong to the top-level group's document.
        afp.begin(FieldType.BNG, "G3").tle("accountNumber", "333");
        page(afp, "P1");
        afp.begin(FieldType.BNG, "INNER").tle("accountNumber", "inner");
        page(afp, "P2");
        page(afp, "P3");
        afp.end(FieldType.ENG).end(FieldType.ENG).end(FieldType.EDT);

        Job job = split(afp, Map.of());

        assertThat(job.documents()).extracting(d -> d.property("pageCount"))
                .containsExactly("3", "1", "3");
        assertEquals("nested group's TLE is not the document's", "333",
                job.documents().get(2).property("accountNumber"));
    }

    @Test
    public void groupMissingRequiredTleIsMarkedNotFatal() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        afp.begin(FieldType.BNG, "G1").tle("accountNumber", "111").tle("zip", "80202");
        page(afp, "P1");
        afp.end(FieldType.ENG);
        afp.begin(FieldType.BNG, "G2").tle("accountNumber", "222").tle("name", "");
        page(afp, "P2");
        afp.end(FieldType.ENG);
        afp.begin(FieldType.BNG, "G3").tle("accountNumber", "333").tle("name", "A")
                .tle("zip", "80301");
        page(afp, "P3");
        afp.end(FieldType.ENG).end(FieldType.EDT);

        Job job = split(afp, Map.of("requiredProperties", List.of("accountNumber", "name", "zip")));

        assertThat(job.documents()).hasSize(3);
        assertThat(job.documents()).extracting(d -> d.property(IndexErrors.PROPERTY))
                .containsExactly("missing: name", "missing: name, zip", null);
    }

    @Test
    public void repeatedTleWithAnotherValueKeepsTheFirstAndIsMarked() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")
                .begin(FieldType.BNG, "G1")
                .tle("zip", "80202").tle("zip", "80202").tle("name", "A").tle("name", "B");
        page(afp, "P1");
        afp.end(FieldType.ENG).end(FieldType.EDT);

        Document doc = split(afp, Map.of()).documents().get(0);

        assertEquals("name", "A", doc.property("name"));
        assertEquals("indexError", "TLE 'name' repeated with another value",
                doc.property(IndexErrors.PROPERTY));
    }

    @Test
    public void inlineResourceGroupIsCopiedIntoEveryDocument() throws Exception {
        AfpBytes afp = AfpBytes.create()
                .begin(FieldType.BRG, "RESOURCE")
                .field(FieldType.NOP, AfpBytes.bytes(1, 2, 3))
                .end(FieldType.ERG)
                .begin(FieldType.BDT, "RUN");
        group(afp, "G1", 1, "accountNumber", "111");
        // Something between groups applies to the group after it.
        afp.field(FieldType.IMM, AfpBytes.name8("MM2"));
        group(afp, "G2", 1, "accountNumber", "222");
        afp.end(FieldType.EDT);

        Job job = split(afp, Map.of());

        List<FieldType> resources = List.of(FieldType.BRG, FieldType.NOP, FieldType.ERG,
                FieldType.BDT);
        assertThat(types(job.documents().get(0).file())).startsWith(resources.toArray(FieldType[]::new))
                .doesNotContain(FieldType.IMM);
        assertThat(types(job.documents().get(1).file())).startsWith(resources.toArray(FieldType[]::new))
                .containsSubsequence(FieldType.BDT, FieldType.IMM, FieldType.BNG);
        StructuredField nop = fields(job.documents().get(1).file()).get(1);
        assertThat(nop.data()).containsExactly(1, 2, 3);
    }

    @Test
    public void laterImmAppliesToEveryFollowingDocument() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")
                .field(FieldType.IMM, AfpBytes.name8("MAPA"));
        group(afp, "G1", 1, "accountNumber", "111");
        afp.field(FieldType.IMM, AfpBytes.name8("MAPB"));
        group(afp, "G2", 1, "accountNumber", "222");
        group(afp, "G3", 1, "accountNumber", "333");
        afp.field(FieldType.IMM, AfpBytes.name8("MAPC"));
        group(afp, "G4", 1, "accountNumber", "444");
        afp.end(FieldType.EDT);

        Job job = split(afp, Map.of());

        assertEquals("document 1", List.of("MAPA"), mediumMaps(job.documents().get(0).file()));
        assertEquals("document 2", List.of("MAPA", "MAPB"),
                mediumMaps(job.documents().get(1).file()));
        // No IMM of its own: MAPB is still in effect, so it's carried in.
        assertEquals("document 3", List.of("MAPA", "MAPB"),
                mediumMaps(job.documents().get(2).file()));
        // Its own IMM replaces the carried one, which isn't written as well.
        assertEquals("document 4", List.of("MAPA", "MAPC"),
                mediumMaps(job.documents().get(3).file()));
        assertThat(types(job.documents().get(2).file()))
                .containsSubsequence(FieldType.BDT, FieldType.IMM, FieldType.IMM, FieldType.BNG);
    }

    @Test
    public void immBetweenAGroupsPagesCarriesIntoTheNextDocument() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        afp.begin(FieldType.BNG, "G1").tle("accountNumber", "111");
        page(afp, "G1P1");
        afp.field(FieldType.IMM, AfpBytes.name8("DUPLEX"));
        page(afp, "G1P2");
        afp.end(FieldType.ENG);
        group(afp, "G2", 1, "accountNumber", "222");
        afp.end(FieldType.EDT);

        Job job = split(afp, Map.of());

        assertEquals("document 1 keeps its own IMM", List.of("DUPLEX"),
                mediumMaps(job.documents().get(0).file()));
        assertEquals("document 2", List.of("DUPLEX"), mediumMaps(job.documents().get(1).file()));
    }

    @Test
    public void closingEndsAreNamedLikeTheirBegins() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        group(afp, "G1", 1, "accountNumber", "111");
        afp.end(FieldType.EDT);

        List<StructuredField> fields = fields(split(afp, Map.of()).documents().get(0).file());

        StructuredField edt = fields.get(fields.size() - 1);
        assertEquals("last field", FieldType.EDT, edt.type());
        assertThat(edt.data()).containsExactly(AfpBytes.name8("RUN"));
    }

    @Test
    public void fileWithNoPageGroupsFailsWithClearMessage() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN").end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).contains("no named page groups").contains("BNG");
    }

    @Test
    public void unbalancedGroupFailsWithOffset() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")   // offset 0, 17 bytes
                .begin(FieldType.BNG, "G1")                              // offset 0x11
                .begin(FieldType.BPG, "P1")                              // offset 0x22
                .end(FieldType.ENG)                                      // offset 0x33: no EPG
                .end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).isEqualTo("The ENG at offset 0x00000033 doesn't close the"
                + " BPG at offset 0x00000022; expected EPG first");
    }

    @Test
    public void unclosedGroupAtEndOfFileFailsWithOffset() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")
                .begin(FieldType.BNG, "G1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).isEqualTo(
                "The BNG at offset 0x00000011 has no ENG before the end of the file");
    }

    @Test
    public void endWithNoBeginFails() throws Exception {
        AfpBytes afp = AfpBytes.create().end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).contains("EDT at offset 0x00000000 has no matching Begin");
    }

    @Test
    public void pageOutsideAnyGroupFails() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        group(afp, "G1", 1, "accountNumber", "111");
        page(afp, "LOOSE");
        afp.end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).contains("BPG at offset").contains("outside any named page");
    }

    @Test
    public void contentAfterTheDocumentEndsFails() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        group(afp, "G1", 1, "accountNumber", "111");
        afp.end(FieldType.EDT).begin(FieldType.BDT, "SECOND");
        group(afp, "G2", 1, "accountNumber", "222");
        afp.end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).contains("BDT at offset").contains("one document");
    }

    @Test
    public void undecodableTleFailsWithOffset() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN")
                .begin(FieldType.BNG, "G1")
                .field(FieldType.TLE, AfpBytes.triplet(0x36, AfpBytes.bytes(0, 0, 0xC1)))
                .end(FieldType.ENG)
                .end(FieldType.EDT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> split(afp, Map.of()));

        assertThat(e.getMessage()).startsWith("The TLE at offset 0x00000022 can't be decoded")
                .contains("no attribute name");
    }

    @Test
    public void bytesThatAreNotAfpFailWithOffset() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN").raw(0x25, 0x50);

        AfpFormatException e = assertThrows(AfpFormatException.class,
                () -> split(afp, Map.of()));

        assertEquals("offset", 0x11, e.offset());
    }

    @Test
    public void unknownCodePageFailsAtLoad() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SplitAfpStep(Map.of("codePage", "NOT-A-CODE-PAGE")));

        assertThat(e.getMessage()).contains("codePage").contains("NOT-A-CODE-PAGE");
    }

    private static void group(AfpBytes afp, String name, int pages, String tle, String value) {
        afp.begin(FieldType.BNG, name).tle(tle, value);
        for (int p = 1; p <= pages; p++) {
            page(afp, name + "P" + p);
        }
        afp.end(FieldType.ENG);
    }

    private static void page(AfpBytes afp, String name) {
        afp.begin(FieldType.BPG, name).end(FieldType.EPG);
    }

    private Job split(AfpBytes afp, Map<String, Object> params) throws Exception {
        Path root = tmp.getRoot().toPath();
        Path input = Files.write(root.resolve("in.afp"), afp.bytes());
        Job job = TestJobs.newJob(root, input);
        new SplitAfpStep(params).execute(job);
        return job;
    }

    static List<StructuredField> fields(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return AfpReader.readAll(in);
        }
    }

    private static List<FieldType> types(Path file) throws IOException {
        return fields(file).stream().map(StructuredField::type).toList();
    }

    /** The medium map names the document's IMMs invoke, in file order. */
    private static List<String> mediumMaps(Path file) throws IOException {
        return fields(file).stream()
                .filter(f -> f.type() == FieldType.IMM)
                .map(f -> Ebcdic.decode(f.data()).strip())
                .toList();
    }
}
