package dev.yjimmy.printlab.testdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public class StatementDataGeneratorTest {

    @Test
    public void sameSeedGivesTheSameStatements() {
        assertEquals(new StatementDataGenerator(7).generate(30, 5),
                new StatementDataGenerator(7).generate(30, 5));
        assertNotEquals(new StatementDataGenerator(7).generate(30, 0),
                new StatementDataGenerator(8).generate(30, 0));
    }

    @Test
    public void statementsHaveOneToThreeFullPages() {
        List<Statement> statements = new StatementDataGenerator(7).generate(50, 0);

        assertThat(statements).hasSize(50).allSatisfy(s -> {
            assertThat(s.accountNumber()).matches("\\d{10}");
            assertThat(s.zip()).matches("\\d{5}");
            assertThat(s.name()).isNotBlank();
            assertThat(s.pageCount()).isBetween(1, 3);
            assertThat(s.pages()).allSatisfy(page ->
                    assertThat(page).hasSize(StatementDataGenerator.LINES_PER_PAGE));
        });
    }

    @Test
    public void badAddressesOnlyChangeTheBadStatements() {
        List<Statement> good = new StatementDataGenerator(7).generate(20, 0);
        List<Statement> bad = new StatementDataGenerator(7).generate(20, 6);

        int blankNames = 0;
        int shortZips = 0;
        for (int i = 0; i < good.size(); i++) {
            Statement g = good.get(i);
            Statement b = bad.get(i);
            assertEquals("account " + i, g.accountNumber(), b.accountNumber());
            assertEquals("pages " + i, g.pages(), b.pages());
            if (b.name().isEmpty()) {
                blankNames++;
                assertEquals("zip " + i, g.zip(), b.zip());
            } else if (!b.zip().equals(g.zip())) {
                shortZips++;
                assertEquals("short zip " + i, g.zip().substring(0, 4), b.zip());
            } else {
                assertEquals("statement " + i, g, b);
            }
        }
        assertEquals("blank names", 3, blankNames);
        assertEquals("short zips", 3, shortZips);
    }

    @Test
    public void rejectsBadCounts() {
        StatementDataGenerator generator = new StatementDataGenerator(7);

        assertThrows(IllegalArgumentException.class, () -> generator.generate(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(3, 4));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(3, -1));
    }

    @Test
    public void aStatementNeedsAPage() {
        assertThrows(IllegalArgumentException.class,
                () -> new Statement("1", "n", "s", "c", "CO", "80202", List.of()));
    }

    @Test
    public void layoutRejectsPagesOutsideTheStatement() {
        Statement statement = new StatementDataGenerator(7).generate(1, 0).get(0);

        assertThrows(IllegalArgumentException.class, () -> StatementLayout.lines(statement, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StatementLayout.lines(statement, statement.pageCount() + 1));
    }
}
