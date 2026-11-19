package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.afp.AfpDump;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.afp.TleDecoder;
import dev.yjimmy.printlab.testdata.Statement;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The documents SplitAfp writes are AFP files in their own right. */
public class SplitAfpOutputTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void everySplitDocumentReadsBackCleanly() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path input = root.resolve("run.afp");
        List<Statement> statements = new StatementDataGenerator(42).generate(12, 2);
        try (var out = Files.newOutputStream(input)) {
            StatementAfpGenerator.write(statements, out);
        }
        Job job = TestJobs.newJob(root, input);

        new SplitAfpStep(Map.of()).execute(job);

        assertThat(job.documents()).hasSize(statements.size());
        for (Document doc : job.documents()) {
            StringBuilder dump = new StringBuilder();
            int problems;
            try (InputStream in = Files.newInputStream(doc.file())) {
                problems = new AfpDump().dump(in, dump);
            }
            assertEquals(doc.file() + "\n" + dump, 0, problems);

            List<StructuredField> fields = SplitAfpStepTest.fields(doc.file());
            assertEquals("first field", FieldType.BDT, fields.get(0).type());
            assertEquals("last field", FieldType.EDT, fields.get(fields.size() - 1).type());
            assertThat(fields).filteredOn(f -> f.type() == FieldType.BNG).hasSize(1);

            Statement statement = statements.get(doc.sequence() - 1);
            assertThat(fields).filteredOn(f -> f.type() == FieldType.BPG)
                    .hasSize(statement.pageCount());
            assertThat(fields).filteredOn(f -> f.type() == FieldType.TLE)
                    .extracting(f -> TleDecoder.decode(f).value())
                    .containsExactly(statement.accountNumber(), statement.name(), statement.zip());
        }
    }
}
