package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

    @Test
    public void codePageNumbersMapToCharsets() {
        assertEquals("37", Ebcdic.IBM037, Ebcdic.forCodePage(37));
        assertEquals("500", "IBM500", Ebcdic.forCodePage(500).name());
        assertEquals("1140 (037 with the euro)", "IBM01140", Ebcdic.forCodePage(1140).name());
        assertEquals("1047 (Open Systems)", "IBM1047", Ebcdic.forCodePage(1047).name());
    }

    @Test
    public void ccsidsMapToCharsets() {
        assertEquals("1208", StandardCharsets.UTF_8, Ebcdic.forCcsid(1208));
        assertEquals("1200", StandardCharsets.UTF_16BE, Ebcdic.forCcsid(1200));
        assertEquals("37", Ebcdic.IBM037, Ebcdic.forCcsid(37));
    }

    @Test
    public void unknownCodePageFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Ebcdic.forCodePage(65534));

        assertThat(e.getMessage()).isEqualTo("code page 65534 (IBM65534) isn't available in this"
                + " JVM");
    }
}
