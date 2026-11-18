package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.util.List;
import org.junit.Test;

public class TleEncoderTest {

    /** The same names and values {@link TleDecoderTest} decodes, plus edge cases. */
    private static final List<Tle> CASES = List.of(
            new Tle("accountNumber", "1234567890"),
            new Tle("name", "AVERY NGUYEN"),
            new Tle("zip", "80202"),
            new Tle("name", ""),
            new Tle("note", "Café, 50% off! <a@b.c> #1"),
            new Tle("n".repeat(TleEncoder.MAX_TEXT_LENGTH), "v".repeat(TleEncoder.MAX_TEXT_LENGTH)));

    @Test
    public void encodeThenDecodeRoundTrips() {
        for (Tle tle : CASES) {
            assertEquals(tle.name(), tle, TleDecoder.decode(TleEncoder.encode(tle), Ebcdic.IBM037));
        }
    }

    @Test
    public void encodesWhatTheTestBuilderWrites() {
        assertArrayEquals(AfpBytes.tleData("accountNumber", "1234567890"),
                TleEncoder.encode("accountNumber", "1234567890"));
    }

    @Test
    public void rejectsCharactersNotInCodePage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleEncoder.encode("name", "Zoë Wang 王"));

        assertThat(e.getMessage()).contains("value").contains("U+738B").contains("IBM037");
        assertThrows(IllegalArgumentException.class, () -> TleEncoder.encode("price€", "1"));
    }

    @Test
    public void rejectsTextLongerThanATriplet() {
        String tooLong = "x".repeat(TleEncoder.MAX_TEXT_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> TleEncoder.encode(tooLong, "v"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleEncoder.encode("n", tooLong));
        assertThat(e.getMessage()).contains("value").contains("251");
    }

    @Test
    public void rejectsAnEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> TleEncoder.encode("", "v"));
    }
}
