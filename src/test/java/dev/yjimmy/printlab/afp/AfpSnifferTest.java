package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** What the first bytes of a wrong file say about it. */
public class AfpSnifferTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void recognisesAPdf() {
        assertThat(AfpSniffer.hint(ascii("%PDF-1.7\n%âãÏÓ"))).contains("is a PDF")
                .contains("SplitPdf");
    }

    @Test
    public void aPdfIsSentOnlyToStepsThatExistOnThisBranch() {
        // SplitPdfByDPart lives on another branch; the hint must not name a step that isn't here.
        assertThat(AfpSniffer.hint(ascii("%PDF-1.7\n"))).doesNotContain("DPart");
    }

    @Test
    public void recognisesPostScript() {
        assertThat(AfpSniffer.hint(ascii("%!PS-Adobe-3.0\n"))).contains("PostScript");
    }

    @Test
    public void recognisesPrinterLanguageStartingWithEscape() {
        assertThat(AfpSniffer.hint(new byte[] {0x1B, 0x45, 0x1B, 0x26, 0x6C})).contains("PCL");
    }

    @Test
    public void recognisesFieldsWithNoIntroducer() {
        // A length of 16, then the D3 that starts every structured field's type.
        assertThat(AfpSniffer.hint(new byte[] {0x00, 0x10, (byte) 0xD3, (byte) 0xA8, (byte) 0xA8}))
                .contains("no 0x5A before it");
    }

    @Test
    public void ebcdicTextWithAnLThirdIsNotTakenForFields() {
        // 0xD3 is EBCDIC "L". The first two letters make a length too big for a field anyway, and
        // the text is checked first.
        byte[] text = "ALL OF THE ABOVE IS DATA".getBytes(Charset.forName("IBM037"));

        assertThat(AfpSniffer.hint(text)).contains("EBCDIC text").doesNotContain("0x5A");
    }

    @Test
    public void textStartingWithASpaceAndLookingLikeAPlausibleLengthIsStillText() {
        // 0x40 0xC1 is a length of 16,577 and the third byte is 0xD3: field-shaped, but text.
        byte[] text = " AL ACCOUNT STATEMENT FOR THE MONTH".getBytes(Charset.forName("IBM037"));

        assertThat(AfpSniffer.hint(text)).contains("EBCDIC text").doesNotContain("0x5A");
    }

    @Test
    public void aFieldNeedsAPlausibleLengthAndTypeToBeRecognised() {
        byte[] tooLong = {(byte) 0xFF, (byte) 0xFF, (byte) 0xD3, (byte) 0xA8, (byte) 0xA8};
        byte[] tooShort = {0x00, 0x04, (byte) 0xD3, (byte) 0xA8, (byte) 0xA8};
        byte[] noClass = {0x00, 0x10, (byte) 0xD3, 0x20, 0x20};
        byte[] noRoom = {0x00, 0x10, (byte) 0xD3, (byte) 0xA8};

        for (byte[] bytes : new byte[][] {tooLong, tooShort, noClass, noRoom}) {
            assertThat(AfpSniffer.hint(bytes)).doesNotContain("no 0x5A");
        }
    }

    @Test
    public void recognisesADescriptorWord() {
        assertThat(AfpSniffer.hint(new byte[] {0x00, 0x15, 0x00, 0x00, 0x7F, 0x7F}))
                .contains("RDW or BDW framed");
    }

    @Test
    public void theDescriptorWordHintCanBeLeftOutForACallerThatTriedThem() {
        assertThat(AfpSniffer.hint(new byte[] {0x00, 0x15, 0x00, 0x00, 0x7F, 0x7F}, false))
                .isEmpty();
    }

    @Test
    public void recognisesAsciiText() {
        assertThat(AfpSniffer.hint(ascii("ACCOUNT 1234567890  JANE DOE\n"))).contains("ASCII text");
    }

    @Test
    public void recognisesEbcdicText() {
        byte[] text = "ACCOUNT 1234567890  JANE DOE".getBytes(Charset.forName("IBM037"));

        assertThat(AfpSniffer.hint(text)).contains("EBCDIC text").contains("page definition");
    }

    @Test
    public void saysNothingAboutAnythingElse() {
        assertThat(AfpSniffer.hint(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x01, 0x02})).isEmpty();
        assertThat(AfpSniffer.hint(new byte[0])).isEmpty();
    }

    @Test
    public void shortInputsAreSafe() {
        // Too short to be a PDF, a PostScript file or a descriptor word; shorter than any test.
        assertThat(AfpSniffer.hint(new byte[] {0x25})).contains("ASCII text");
        assertThat(AfpSniffer.hint(ascii("%PD"))).doesNotContain("is a PDF");
        assertThat(AfpSniffer.hint(new byte[] {0x00, 0x15})).isEmpty();
    }
}
