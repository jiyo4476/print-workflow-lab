package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 2, true),
                        org.assertj.core.groups.Tuple.tuple(3, 4, true));
        assertThat(tree.atLevel(2)).extracting(DPart::startPage, DPart::endPage)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 2),
                        org.assertj.core.groups.Tuple.tuple(3, 4));
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

    private static DPartTree tree(byte[] bytes) {
        return DPartTree.read(new PdfDocumentReader(bytes)).orElseThrow();
    }

    /** Catalog, page tree and {@code count} pages (objects 3 and up); page n is object n + 2. */
    private static PdfBytes withPages(int count, String dpartRoot) {
        String catalog = "<< /Type /Catalog /Pages 2 0 R"
                + (dpartRoot == null ? "" : " /DPartRoot " + dpartRoot) + " >>";
        PdfBytes pdf = new PdfBytes("1.7").object(1, catalog);
        StringBuilder kids = new StringBuilder();
        for (int page = 1; page <= count; page++) {
            kids.append(pageObject(page)).append(" 0 R ");
        }
        pdf.object(2, "<< /Type /Pages /Kids [" + kids + "] /Count " + count + " >>");
        for (int page = 1; page <= count; page++) {
            pdf.object(pageObject(page), "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
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
