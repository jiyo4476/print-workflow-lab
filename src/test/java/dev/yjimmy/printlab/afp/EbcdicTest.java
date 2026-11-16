package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.nio.charset.Charset;
import org.junit.Test;

public class EbcdicTest {

    @Test
    public void decodesUppercaseDigitsAndSpace() {
        assertEquals("C1 C2 F1 40", "AB1 ", Ebcdic.decode(bytes(0xC1, 0xC2, 0xF1, 0x40)));
    }

    @Test
    public void roundTripsAscii() {
        String text = "accountNumber 1234567890, Avery Nguyen <x@y.z> 80202-1234!";

        byte[] encoded = Ebcdic.encode(text);

        assertEquals("round trip", text, Ebcdic.decode(encoded));
        assertThat(encoded).as("lowercase a is 0x81, not ASCII 0x61").startsWith(0x81);
    }

    @Test
    public void otherCodePagesCanBeUsed() {
        // '[' is 0xBA in IBM037 but 0x4A in IBM500 (International).
        Charset ibm500 = Charset.forName("IBM500");

        assertEquals("IBM500", "[", Ebcdic.decode(bytes(0x4A), 0, 1, ibm500));
        assertThat(Ebcdic.encode("[", Ebcdic.IBM037)).containsExactly(0xBA);
    }
}
