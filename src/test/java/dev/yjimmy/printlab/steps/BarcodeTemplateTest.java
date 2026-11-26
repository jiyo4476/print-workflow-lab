package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.Map;
import org.junit.Test;

public class BarcodeTemplateTest {

    @Test
    public void substitutesJobDocumentAndPageValues() {
        BarcodeTemplate template = new BarcodeTemplate(
                "${jobId}|${sequence}|${page}|${pageCount}|${accountNumber}");

        String value = template.render(Map.of("jobId", "job7", "sequence", "3", "page", "2",
                "pageCount", "5", "accountNumber", "1234"), 3);

        assertThat(value).isEqualTo("job7|3|2|5|1234");
    }

    @Test
    public void valueIsTakenLiterallyEvenWhenItLooksLikeAPlaceholder() {
        BarcodeTemplate template = new BarcodeTemplate("${name}");

        String literal = "$1 \\ ${x}";

        assertThat(template.render(Map.of("name", literal), 1)).isEqualTo(literal);
    }

    @Test
    public void unknownPlaceholderNamesTheDocumentAndThePlaceholder() {
        BarcodeTemplate template = new BarcodeTemplate("${accountNumber}${nope}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> template.render(Map.of("accountNumber", "1"), 12));

        assertThat(e.getMessage()).contains("Document 12").contains("${nope}");
    }

    @Test
    public void emptyTemplateIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new BarcodeTemplate(""));

        assertThat(e.getMessage()).contains("template");
    }
}
