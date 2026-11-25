package dev.yjimmy.printlab.testdata;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.pdf.DPartTree;
import dev.yjimmy.printlab.pdf.DPartTree.DPart;
import dev.yjimmy.printlab.pdf.PdfArray;
import dev.yjimmy.printlab.pdf.PdfDictionary;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfName;
import dev.yjimmy.printlab.pdf.PdfNumber;
import dev.yjimmy.printlab.pdf.PdfObject;
import dev.yjimmy.printlab.pdf.PdfReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What {@code generate --dpart} writes: one DPart per statement, covering every page once. */
public class DPartWriterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyPageCoveredExactlyOnce() throws Exception {
        List<Statement> statements = new StatementDataGenerator(11).generate(40, 3);
        int totalPages = statements.stream().mapToInt(Statement::pageCount).sum();
        Path out = write(statements);

        List<DPart> documents = tree(out).atLevel(1);

        assertThat(documents).hasSize(statements.size());
        int nextPage = 1;
        for (DPart document : documents) {
            assertThat(document.startPage()).as("first page").isEqualTo(nextPage);
            nextPage = document.endPage() + 1;
        }
        assertThat(nextPage - 1).as("pages covered").isEqualTo(totalPages);
    }

    @Test
    public void rangesAreOrderedAndStartBeforeEnd() throws Exception {
        Path out = write(new StatementDataGenerator(12).generate(40, 3));

        List<DPart> nodes = tree(out).nodes();

        assertThat(nodes).isNotEmpty();
        for (int i = 0; i < nodes.size(); i++) {
            assertThat(nodes.get(i).startPage()).isLessThanOrEqualTo(nodes.get(i).endPage());
            if (i > 0) {
                assertThat(nodes.get(i).startPage()).as("node %d starts after the one before", i)
                        .isGreaterThan(nodes.get(i - 1).endPage());
            }
        }
    }

    @Test
    public void dpmEntriesMatchTheStatements() throws Exception {
        List<Statement> statements = new StatementDataGenerator(11).generate(12, 2);
        Path out = write(statements);

        List<DPart> documents = tree(out).atLevel(1);

        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            assertThat(documents.get(i).dpm()).isEqualTo(Map.of(
                    "AccountNumber", statement.accountNumber(),
                    "Name", statement.name(),
                    "Zip", statement.zip()));
            assertThat(documents.get(i).endPage() - documents.get(i).startPage() + 1)
                    .isEqualTo(statement.pageCount());
        }
    }

    @Test
    public void writesThePdf20Shape() throws Exception {
        List<Statement> statements = new StatementDataGenerator(11).generate(5, 0);
        Path out = write(statements);
        PdfDocumentReader pdf = PdfDocumentReader.open(out);

        PdfObject rootRef = pdf.catalog().get("DPartRoot");
        PdfDictionary root = (PdfDictionary) pdf.resolve(rootRef);
        assertThat(root.get("Type")).isEqualTo(PdfName.of("DPartRoot"));
        assertThat(((PdfNumber) root.get("RecordLevel")).intValue()).isEqualTo(1);
        assertThat(root.get("NodeNameList"))
                .isEqualTo(PdfArray.of(PdfName.of("root"), PdfName.of("record")));

        PdfReference rootNodeRef = (PdfReference) root.get("DPartRootNode");
        PdfDictionary rootNode = (PdfDictionary) pdf.resolve(rootNodeRef);
        assertThat(rootNode.get("Parent")).isEqualTo(rootRef);
        // /DParts is an array holding one array of every statement's node.
        PdfArray groups = (PdfArray) pdf.resolve(rootNode.get("DParts"));
        assertThat(groups.size()).isEqualTo(1);
        PdfArray siblings = (PdfArray) pdf.resolve(groups.get(0));
        assertThat(siblings.size()).isEqualTo(statements.size());
        for (PdfObject sibling : siblings.items()) {
            PdfDictionary node = (PdfDictionary) pdf.resolve(sibling);
            assertThat(node.get("Type")).isEqualTo(PdfName.of("DPart"));
            assertThat(node.get("Parent")).isEqualTo(rootNodeRef);
        }
        assertThat(tree(out).recordLevel()).hasValue(1);
    }

    @Test
    public void plainWriteHasNoDpartTree() throws Exception {
        Path out = folder.getRoot().toPath().resolve("plain.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(11).generate(3, 0), out);

        assertThat(DPartTree.read(PdfDocumentReader.open(out))).isEmpty();
    }

    private Path write(List<Statement> statements) throws Exception {
        Path out = folder.getRoot().toPath().resolve("run.pdf");
        StatementPdfGenerator.write(statements, out, true);
        return out;
    }

    private static DPartTree tree(Path file) throws Exception {
        return DPartTree.read(PdfDocumentReader.open(file)).orElseThrow();
    }
}
