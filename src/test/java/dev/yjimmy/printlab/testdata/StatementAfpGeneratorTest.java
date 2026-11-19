package dev.yjimmy.printlab.testdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.afp.AfpDump;
import dev.yjimmy.printlab.afp.AfpReader;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.afp.Tle;
import dev.yjimmy.printlab.afp.TleDecoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StatementAfpGeneratorTest {

    private static final long SEED = 42;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sameSeedProducesIdenticalBytes() throws IOException {
        Path first = tmp.getRoot().toPath().resolve("first.afp");
        Path second = tmp.getRoot().toPath().resolve("second.afp");

        new StatementAfpGenerator(SEED).generate(20, 4, first);
        new StatementAfpGenerator(SEED).generate(20, 4, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    public void groupCountEqualsStatementCount() {
        List<StructuredField> fields = fields(new StatementDataGenerator(SEED).generate(25, 0));

        assertThat(fields).filteredOn(f -> f.type() == FieldType.BNG).hasSize(25);
        assertThat(fields).filteredOn(f -> f.type() == FieldType.ENG).hasSize(25);
    }

    @Test
    public void everyGroupHasAccountNumberNameAndZipTles() {
        List<Statement> statements = new StatementDataGenerator(SEED).generate(25, 0);

        List<Group> groups = groups(fields(statements));

        assertEquals("groups", statements.size(), groups.size());
        for (int i = 0; i < groups.size(); i++) {
            Statement statement = statements.get(i);
            assertEquals("group " + i + " TLEs", Map.of(
                    "accountNumber", statement.accountNumber(),
                    "name", statement.name(),
                    "zip", statement.zip()), groups.get(i).tles());
        }
    }

    @Test
    public void badAddressesBecomeAnEmptyNameOrAShortZipTle() {
        List<Group> groups = groups(fields(new StatementDataGenerator(SEED).generate(20, 4)));

        assertThat(groups).filteredOn(g -> g.tles().get("name").isEmpty()).hasSize(2);
        assertThat(groups).filteredOn(g -> g.tles().get("zip").length() == 4).hasSize(2);
        assertThat(groups).allSatisfy(g -> assertThat(g.tles()).containsOnlyKeys(
                "accountNumber", "name", "zip"));
    }

    @Test
    public void legacyTleNamesOptionWritesProductionStyleNames() throws IOException {
        List<Statement> statements = new StatementDataGenerator(SEED).generate(5, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StatementAfpGenerator.write(statements, StatementAfpGenerator.TleNames.LEGACY, out);

        List<Group> groups = groups(AfpReader.readAll(new ByteArrayInputStream(out.toByteArray())));
        for (int i = 0; i < groups.size(); i++) {
            Statement statement = statements.get(i);
            assertEquals("group " + i + " TLEs", Map.of(
                    "ACCT_NO", statement.accountNumber(),
                    "CUST_NAME", statement.name(),
                    "ZIP_CODE", statement.zip()), groups.get(i).tles());
        }
        // Only the names differ: the pages are the same as with the default names.
        assertEquals(groups(fields(statements)).stream().map(Group::pages).toList(),
                groups.stream().map(Group::pages).toList());
        assertEquals(StatementAfpGenerator.TleNames.LEGACY,
                StatementAfpGenerator.TleNames.named("legacy"));
        assertEquals(StatementAfpGenerator.TleNames.PROPERTY_NAMES,
                StatementAfpGenerator.TleNames.named("property"));
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> StatementAfpGenerator.TleNames.named("LEGACY")).getMessage())
                .contains("'property' or 'legacy'");
    }

    @Test
    public void beginsAndEndsAreBalanced() {
        List<StructuredField> fields = fields(new StatementDataGenerator(SEED).generate(10, 2));

        Deque<StructuredField> open = new ArrayDeque<>();
        for (StructuredField field : fields) {
            if (field.isBegin()) {
                open.push(field);
            } else if (field.isEnd()) {
                assertThat(open).as("Begin for %s", field).isNotEmpty();
                assertEquals(field + " closes the innermost Begin",
                        open.pop().matchingEndId(), field.typeId());
            }
        }
        assertThat(open).as("unclosed Begins").isEmpty();
        assertEquals("first field", FieldType.BDT, fields.get(0).type());
        assertEquals("last field", FieldType.EDT, fields.get(fields.size() - 1).type());
    }

    @Test
    public void dumpFindsNoProblems() {
        byte[] afp = bytes(new StatementDataGenerator(SEED).generate(10, 2));
        StringBuilder out = new StringBuilder();

        int problems = new AfpDump().dump(new ByteArrayInputStream(afp), out);

        assertEquals(out.toString(), 0, problems);
    }

    @Test
    public void everyPageHasAnEnvironmentAndText() {
        List<StructuredField> fields = fields(new StatementDataGenerator(SEED).generate(3, 0));

        List<FieldType> page = new ArrayList<>();
        for (StructuredField field : fields) {
            if (field.type() == FieldType.BPG) {
                page.clear();
            }
            page.add(field.type());
            if (field.type() == FieldType.EPG) {
                assertEquals(List.of(FieldType.BPG, FieldType.BAG, FieldType.MCF, FieldType.PGD,
                        FieldType.PTD, FieldType.EAG, FieldType.BPT, FieldType.PTX, FieldType.EPT,
                        FieldType.EPG), page);
            }
        }
    }

    @Test
    public void pageTextIsTheLayoutsTextAtItsPositions() {
        Statement statement = new StatementDataGenerator(SEED).generate(1, 0).get(0);

        List<Placed> placed = PtocaText.decode(StatementAfpGenerator.pageText(statement, 1));

        List<Placed> expected = StatementLayout.lines(statement, 1).stream()
                .map(l -> new Placed(l.x() * 20, l.y() * 20, l.text())).toList();
        assertEquals(expected, placed);
    }

    @Test
    public void contentMatchesPdfGeneratorForSameSeed() throws IOException {
        Path pdf = tmp.getRoot().toPath().resolve("run.pdf");
        Path afp = tmp.getRoot().toPath().resolve("run.afp");
        new StatementPdfGenerator(SEED).generate(15, 4, pdf);
        new StatementAfpGenerator(SEED).generate(15, 4, afp);

        List<Group> groups = groups(AfpReader.readAll(Files.newInputStream(afp)));
        List<List<String>> pdfStatements = pdfStatements(pdf);

        assertEquals("statements", pdfStatements.size(), groups.size());
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            List<String> pages = pdfStatements.get(i);
            Map<String, String> tles = group.tles();
            assertEquals("statement " + i + " pages", pages.size(), group.pages().size());
            for (int p = 0; p < pages.size(); p++) {
                assertThat(pages.get(p)).as("PDF statement %d page %d", i, p + 1)
                        .contains(tles.get("accountNumber"));
                // PDFBox joins text on one baseline into a line, and the AFP writes each piece
                // separately, so compare the text in drawing order without the whitespace.
                assertEquals("statement " + i + " page " + (p + 1) + " text",
                        withoutWhitespace(pages.get(p)),
                        withoutWhitespace(String.join("", group.pages().get(p))));
            }
            assertThat(pages.get(0)).contains(tles.get("zip"));
            if (!tles.get("name").isEmpty()) {
                assertThat(pages.get(0)).contains(tles.get("name"));
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A page group as read back: its TLEs, and the text of each page in PTOCA order. */
    private record Group(Map<String, String> tles, List<List<String>> pages) {
    }

    record Placed(int x, int y, String text) {
    }

    private static byte[] bytes(List<Statement> statements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            StatementAfpGenerator.write(statements, out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static List<StructuredField> fields(List<Statement> statements) {
        return AfpReader.readAll(new ByteArrayInputStream(bytes(statements)));
    }

    private static List<Group> groups(List<StructuredField> fields) {
        List<Group> groups = new ArrayList<>();
        Map<String, String> tles = null;
        List<List<String>> pages = null;
        for (StructuredField field : fields) {
            switch (field.type()) {
                case BNG -> {
                    tles = new LinkedHashMap<>();
                    pages = new ArrayList<>();
                }
                case TLE -> {
                    Tle tle = TleDecoder.decode(field);
                    assertTrue("duplicate TLE " + tle.name(),
                            tles.put(tle.name(), tle.value()) == null);
                }
                case PTX -> pages.add(PtocaText.decode(field.data()).stream()
                        .map(Placed::text).toList());
                case ENG -> groups.add(new Group(tles, pages));
                default -> {
                }
            }
        }
        return groups;
    }

    /** The PDF's text, one string per page, grouped into statements by "Page 1 of". */
    private static List<List<String>> pdfStatements(Path pdf) throws IOException {
        List<List<String>> statements = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                Matcher page = Pattern.compile("Page (\\d+) of \\d+").matcher(text);
                assertTrue("page " + p + " has a page number", page.find());
                if (page.group(1).equals("1")) {
                    statements.add(new ArrayList<>());
                }
                statements.get(statements.size() - 1).add(text);
            }
        }
        return statements;
    }

    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** Decodes the PTOCA this generator writes: SCFL, then AMB/AMI/TRN chains. */
    static final class PtocaText {

        static List<Placed> decode(byte[] ptx) {
            List<Placed> placed = new ArrayList<>();
            int x = 0;
            int y = 0;
            int i = 0;
            boolean chained = false;
            while (i < ptx.length) {
                if (!chained) {
                    assertEquals("escape at " + i, 0x2B, ptx[i] & 0xFF);
                    assertEquals("escape at " + (i + 1), 0xD3, ptx[i + 1] & 0xFF);
                    i += 2;
                }
                int length = ptx[i] & 0xFF;
                int type = ptx[i + 1] & 0xFF;
                chained = (type & 1) == 1;
                switch (type & 0xFE) {
                    case 0xF0 -> assertEquals("font local ID", 1, ptx[i + 2]);
                    case 0xD2 -> y = ((ptx[i + 2] & 0xFF) << 8) | (ptx[i + 3] & 0xFF);
                    case 0xC6 -> x = ((ptx[i + 2] & 0xFF) << 8) | (ptx[i + 3] & 0xFF);
                    case 0xDA -> placed.add(new Placed(x, y,
                            Ebcdic.decode(ptx, i + 2, length - 2, Ebcdic.IBM037)));
                    default -> throw new AssertionError(String.format(
                            "unexpected control sequence %02X at %d", type, i));
                }
                i += length;
            }
            assertTrue("the last control sequence ends its chain", !chained);
            return placed;
        }
    }
}
