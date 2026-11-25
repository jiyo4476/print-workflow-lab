package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.yjimmy.printlab.pdf.DPartTree.DPart;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Reads hand-built files whose DPart trees are written out byte by byte. */
public class DPartReaderTest {

    @Test
    public void fileWithoutTreeHasNoDPartTree() {
        byte[] bytes = withPages(2, null).build("/Root 1 0 R");

        assertThat(DPartTree.read(new PdfDocumentReader(bytes))).isEmpty();
    }

    @Test
    public void readsFlatTree() {
        byte[] bytes = withPages(4, "10 0 R")
                .object(10, "<< /DParts [11 0 R 12 0 R] >>")
                .object(11, node(1, 2, "/DPM << /AccountNumber (1111111111) /Name (AVERY)"
                        + " /Zip (80202) >>"))
                .object(12, node(3, 4, "/DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >>"))
                .build("/Root 1 0 R");

        List<DPart> documents = tree(bytes).atLevel(1);

        assertThat(documents).containsExactly(
                new DPart(1, 1, 2, false, Map.of("AccountNumber", "1111111111", "Name", "AVERY",
                        "Zip", "80202")),
                new DPart(1, 3, 4, false, Map.of("AccountNumber", "2222222222", "Name", "BLAKE",
                        "Zip", "10001")));
    }

    @Test
    public void readsTwoLevelTreeAtConfiguredRecordLevel() {
        byte[] bytes = withPages(4, "10 0 R")
                .object(10, "<< /DParts [11 0 R 14 0 R] >>")
                .object(11, node(1, 2, "/DParts [12 0 R 13 0 R]"))
                .object(12, node(1, 1, ""))
                .object(13, node(2, 2, ""))
                .object(14, node(3, 4, "/DParts [15 0 R]"))
                .object(15, node(3, 4, ""))
                .build("/Root 1 0 R");
        DPartTree tree = tree(bytes);

        assertThat(tree.atLevel(1)).extracting(DPart::startPage, DPart::endPage, DPart::hasChildren)
                .containsExactly(tuple(1, 2, true), tuple(3, 4, true));
        assertThat(tree.atLevel(2)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 1), tuple(2, 2), tuple(3, 4));
    }

    @Test
    public void numbersAndNamesInDpmAreReadAsText() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 1, "/DPM << /Zip 80202 /Kind /Statement >>"))
                .build("/Root 1 0 R");

        assertThat(tree(bytes).atLevel(1).get(0).dpm())
                .isEqualTo(Map.of("Zip", "80202", "Kind", "Statement"));
    }

    @Test
    public void nodeWithoutChildrenAboveTheLevelFails() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 2, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes).atLevel(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pages 1-2 has no children")
                .hasMessageContaining("level 2");
    }

    @Test
    public void overlappingRangesFail() {
        byte[] bytes = withPages(4, "10 0 R")
                .object(10, "<< /DParts [11 0 R 12 0 R] >>")
                .object(11, node(1, 2, ""))
                .object(12, node(2, 4, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes).atLevel(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap")
                .hasMessageContaining("share page 2");
    }

    @Test
    public void rangeThatEndsBeforeItStartsFails() {
        byte[] bytes = withPages(3, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(3, 1, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before it starts on page 3");
    }

    @Test
    public void startThatIsNotAPageFails() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /Start 99 0 R /End 4 0 R >>")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("points to object 99, which is not a page");
    }

    @Test
    public void nodeReachedTwiceFails() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R 11 0 R] >>")
                .object(11, node(1, 2, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reached twice");
    }

    @Test
    public void levelMustBeOneOrMore() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 1, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes).atLevel(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level must be 1 or more");
    }

    // The PDF 2.0 shape: /DPartRootNode, /DParts as an array of arrays, /Parent, /RecordLevel.

    @Test
    public void readsThePdf20ShapeAndItsRecordLevel() {
        byte[] bytes = withPages(3, "10 0 R")
                .object(10, "<< /Type /DPartRoot /DPartRootNode 11 0 R /RecordLevel 1"
                        + " /NodeNameList [/root /record] >>")
                .object(11, "<< /Type /DPart /Parent 10 0 R /DParts [[12 0 R 13 0 R]] >>")
                .object(12, node(1, 2, "/Type /DPart /Parent 11 0 R /DPM << /Zip (80202) >>"))
                .object(13, node(3, 3, "/Type /DPart /Parent 11 0 R /DPM << /Zip (10001) >>"))
                .build("/Root 1 0 R");
        DPartTree tree = tree(bytes);

        assertThat(tree.recordLevel()).hasValue(1);
        assertThat(tree.atLevel(1)).extracting(DPart::startPage, DPart::endPage,
                part -> part.dpm().get("Zip"))
                .containsExactly(tuple(1, 2, "80202"), tuple(3, 3, "10001"));
    }

    @Test
    public void recordLevelIsEmptyWhenTheFileHasNone() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 1, ""))
                .build("/Root 1 0 R");

        assertThat(tree(bytes).recordLevel()).isEmpty();
    }

    @Test
    public void flatAndNestedDpartsArraysGiveTheSameNodes() {
        byte[] flat = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R 12 0 R] >>")
                .object(11, node(1, 1, ""))
                .object(12, node(2, 2, ""))
                .build("/Root 1 0 R");
        byte[] nested = withPages(2, "10 0 R")
                .object(10, "<< /DPartRootNode 13 0 R >>")
                .object(13, "<< /DParts [[11 0 R 12 0 R]] >>")
                .object(11, node(1, 1, ""))
                .object(12, node(2, 2, ""))
                .build("/Root 1 0 R");

        assertThat(tree(nested).nodes()).isEqualTo(tree(flat).nodes());
    }

    @Test
    public void branchRangeIsDerivedFromItsChildren() {
        byte[] bytes = withPages(5, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /DParts [[12 0 R 13 0 R]] >>")
                .object(12, node(2, 3, ""))
                .object(13, node(4, 5, ""))
                .build("/Root 1 0 R");

        // Page 1 is in no node; the branch covers what its children cover.
        assertThat(tree(bytes).atLevel(1)).containsExactly(new DPart(1, 2, 5, true, Map.of()));
    }

    @Test
    public void branchWithOnlyOneEndOfItsRangeDerivesTheOther() {
        byte[] bytes = withPages(4, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /Start 3 0 R /DParts [12 0 R 13 0 R] >>")
                .object(12, node(1, 2, ""))
                .object(13, node(3, 4, ""))
                .build("/Root 1 0 R");

        assertThat(tree(bytes).atLevel(1)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 4));
    }

    @Test
    public void branchRangeMustContainItsChildren() {
        byte[] bytes = withPages(4, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 2, "/DParts [12 0 R]"))
                .object(12, node(2, 4, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pages 1-2 contains a DPart for pages 2-4")
                .hasMessageContaining("outside its range");
    }

    @Test
    public void aLeafNeedsStart() {
        byte[] noStart = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /End 4 0 R >>")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(noStart)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no /Start");
    }

    @Test
    public void aLeafWithoutEndIsOnePage() {
        // /End is only written for a node that covers more than one page.
        byte[] bytes = withPages(3, "10 0 R")
                .object(10, "<< /DParts [11 0 R 12 0 R] >>")
                .object(11, "<< /Start 3 0 R >>")
                .object(12, "<< /Start 4 0 R /End 5 0 R >>")
                .build("/Root 1 0 R");

        assertThat(tree(bytes).atLevel(1)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 1), tuple(2, 3));
    }

    @Test
    public void branchWithNoChildrenAndNoRangeFails() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /DParts [] >>")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no /Start");
    }

    // Hostile or broken files.

    @Test
    public void sharedDpartsArrayFails() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R 12 0 R] >>")
                .object(11, "<< /Start 3 0 R /End 3 0 R /DParts 20 0 R >>")
                .object(12, "<< /Start 4 0 R /End 4 0 R /DParts 20 0 R >>")
                .object(20, "[ << /Start 3 0 R /End 3 0 R >> ]")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DParts array object 20 is reached twice");
    }

    @Test
    public void dpartsArrayThatContainsItselfFails() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts 20 0 R >>")
                .object(20, "[ << /Start 3 0 R /End 3 0 R /DParts 20 0 R >> ]")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reached twice");
    }

    @Test
    public void groupsNestedMoreThanOneLevelFail() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts [[[11 0 R]]] >>")
                .object(11, node(1, 1, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nested more than one level deep");
    }

    @Test
    public void nestingBeyondTheDepthCapFails() {
        String nested = "<< /Start 3 0 R /End 3 0 R >>";
        for (int i = 0; i < DPartTree.MAX_DEPTH + 1; i++) {
            nested = "<< /DParts [" + nested + "] >>";
        }
        byte[] bytes = withPages(1, "10 0 R").object(10, nested).build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deeper than " + DPartTree.MAX_DEPTH);
    }

    @Test
    public void malformedPartsFailWithAMessage() {
        byte[] rootNotDictionary = withPages(1, "10 0 R").object(10, "[ ]").build("/Root 1 0 R");
        byte[] rootNodeNotDictionary = withPages(1, "10 0 R")
                .object(10, "<< /DPartRootNode 5 >>").build("/Root 1 0 R");
        byte[] dpartsNotArray = withPages(1, "10 0 R")
                .object(10, "<< /DParts 5 >>").build("/Root 1 0 R");
        byte[] nodeNotDictionary = withPages(1, "10 0 R")
                .object(10, "<< /DParts [5] >>").build("/Root 1 0 R");
        byte[] startNotReference = withPages(1, "10 0 R")
                .object(10, "<< /DParts [ << /Start 3 /End 3 0 R >> ] >>").build("/Root 1 0 R");
        byte[] dpmNotDictionary = withPages(1, "10 0 R")
                .object(10, "<< /DParts [ " + node(1, 1, "/DPM (x)") + " ] >>").build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(rootNotDictionary))
                .hasMessageContaining("/DPartRoot must be a dictionary");
        assertThatThrownBy(() -> tree(rootNodeNotDictionary))
                .hasMessageContaining("/DPartRootNode must be a dictionary");
        assertThatThrownBy(() -> tree(dpartsNotArray))
                .hasMessageContaining("/DParts must be an array");
        assertThatThrownBy(() -> tree(nodeNotDictionary))
                .hasMessageContaining("isn't a dictionary");
        assertThatThrownBy(() -> tree(startNotReference))
                .hasMessageContaining("/Start must be a reference to a page");
        assertThatThrownBy(() -> tree(dpmNotDictionary))
                .hasMessageContaining("/DPM must be a dictionary");
    }

    @Test
    public void readsTheTreeWithoutDecodingAnyPageContent() {
        // Every page's content is a stream with a filter nothing here can decode, plus junk.
        PdfBytes pdf = withPages(2, "10 0 R", true)
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 2, ""));
        pdf.stream(30, "/Filter /NoSuchDecode", "not a content stream \u0000\u0001".getBytes());
        pdf.stream(31, "/Filter /NoSuchDecode", "also junk".getBytes());

        assertThat(tree(pdf.build("/Root 1 0 R")).atLevel(1))
                .extracting(DPart::startPage, DPart::endPage).containsExactly(tuple(1, 2));
    }

    @Test
    public void pageReferenceMatchesOnGenerationToo() {
        // Object 3 generation 0 is page 1; "3 5 R" is a different (missing) object, not a page.
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /Start 3 5 R /End 3 0 R >>")
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which is not a page");
    }

    // /RecordLevel, empty /DParts and an ambiguous root.

    @Test
    public void aBadRecordLevelFails() {
        for (String bad : List.of("0", "-1", "1.5", "(one)", "/One", "[1]")) {
            byte[] bytes = withPages(1, "10 0 R")
                    .object(10, "<< /DPartRootNode 11 0 R /RecordLevel " + bad + " >>")
                    .object(11, "<< /DParts [12 0 R] >>")
                    .object(12, node(1, 1, ""))
                    .build("/Root 1 0 R");

            assertThatThrownBy(() -> tree(bytes))
                    .as("/RecordLevel " + bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("The file's /RecordLevel must be a whole number of 1 or"
                            + " more");
        }
    }

    @Test
    public void aRecordLevelThatIsAReferenceIsResolved() {
        byte[] bytes = withPages(1, "10 0 R")
                .object(10, "<< /DPartRootNode 11 0 R /RecordLevel 13 0 R >>")
                .object(11, "<< /DParts [12 0 R] >>")
                .object(12, node(1, 1, ""))
                .object(13, "2")
                .build("/Root 1 0 R");

        assertThat(tree(bytes).recordLevel()).hasValue(2);
    }

    @Test
    public void anEmptyDpartsMakesALeafThatNeedsItsOwnRange() {
        byte[] withRange = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, node(1, 2, "/DParts []"))
                .build("/Root 1 0 R");
        byte[] withoutRange = withPages(2, "10 0 R")
                .object(10, "<< /DParts [11 0 R] >>")
                .object(11, "<< /DParts [] >>")
                .build("/Root 1 0 R");

        // No children, so it is not a branch: asking for a deeper level says so.
        assertThat(tree(withRange).nodes()).extracting(DPart::hasChildren).containsExactly(false);
        assertThatThrownBy(() -> tree(withRange).atLevel(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no children");
        assertThatThrownBy(() -> tree(withoutRange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no /Start page (its /DParts is empty)");
    }

    @Test
    public void aRootWithBothARootNodeAndItsOwnDpartsIsAmbiguous() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DPartRootNode 11 0 R /DParts [12 0 R] >>")
                .object(11, "<< /DParts [12 0 R] >>")
                .object(12, node(1, 2, ""))
                .build("/Root 1 0 R");

        assertThatThrownBy(() -> tree(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both /DPartRootNode and /DParts");
    }

    @Test
    public void aTreeThreeDeepReadsAtEachLevelWhateverTheRecordLevel() {
        byte[] bytes = withPages(2, "10 0 R")
                .object(10, "<< /DPartRootNode 11 0 R /RecordLevel 2 >>")
                .object(11, "<< /DParts [[12 0 R]] >>")
                .object(12, "<< /DParts [[13 0 R 14 0 R]] >>")
                .object(13, node(1, 1, "/DParts [15 0 R]"))
                .object(14, node(2, 2, "/DParts [16 0 R]"))
                .object(15, node(1, 1, ""))
                .object(16, node(2, 2, ""))
                .build("/Root 1 0 R");
        DPartTree tree = tree(bytes);

        assertThat(tree.recordLevel()).hasValue(2);
        assertThat(tree.atLevel(1)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 2));
        assertThat(tree.atLevel(2)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 1), tuple(2, 2));
        assertThat(tree.atLevel(3)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(tuple(1, 1), tuple(2, 2));
    }

    private static DPartTree tree(byte[] bytes) {
        return DPartTree.read(new PdfDocumentReader(bytes)).orElseThrow();
    }

    /** Catalog, page tree and {@code count} pages (objects 3 and up); page n is object n + 2. */
    private static PdfBytes withPages(int count, String dpartRoot) {
        return withPages(count, dpartRoot, false);
    }

    /**
     * As above; with {@code contents}, page n has {@code /Contents} pointing at object 29 + n,
     * which the caller writes.
     */
    private static PdfBytes withPages(int count, String dpartRoot, boolean contents) {
        String catalog = "<< /Type /Catalog /Pages 2 0 R"
                + (dpartRoot == null ? "" : " /DPartRoot " + dpartRoot) + " >>";
        PdfBytes pdf = new PdfBytes("1.7").object(1, catalog);
        StringBuilder kids = new StringBuilder();
        for (int page = 1; page <= count; page++) {
            kids.append(pageObject(page)).append(" 0 R ");
        }
        pdf.object(2, "<< /Type /Pages /Kids [" + kids + "] /Count " + count + " >>");
        for (int page = 1; page <= count; page++) {
            pdf.object(pageObject(page), "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
                    + (contents ? " /Contents " + (29 + page) + " 0 R" : "") + " >>");
        }
        return pdf;
    }

    /** A node for pages {@code first} to {@code last} (1-based), with {@code extra} entries. */
    private static String node(int first, int last, String extra) {
        return "<< /Start " + pageObject(first) + " 0 R /End " + pageObject(last) + " 0 R " + extra
                + " >>";
    }

    private static int pageObject(int page) {
        return page + 2;
    }
}
