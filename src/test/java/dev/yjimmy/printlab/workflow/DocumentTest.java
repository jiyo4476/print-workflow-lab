package dev.yjimmy.printlab.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

public class DocumentTest {

    @Test
    public void firstInputPositionIsAccepted() {
        Document doc = new Document(1, Path.of("a.pdf"), Map.of(), 1);

        assertThat(doc.inputSequence()).isEqualTo(1);
    }

    @Test
    public void zeroInputSequenceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Document(1, Path.of("a.pdf"), Map.of(), 0));

        assertThat(e.getMessage()).contains("inputSequence must be 1 or more, not 0");
    }

    @Test
    public void negativeInputSequenceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Document(1, Path.of("a.pdf"), Map.of(), -3));

        assertThat(e.getMessage()).contains("not -3");
    }
}
