package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.Test;

public class PageBuilderTest {

    @Test
    public void pageDescriptorRoundTripsItsUnitsAndSize() {
        PageBuilder.PageSize size = PageBuilder.decodePgd(PageBuilder.pageDescriptor(12_240,
                15_840));

        assertThat(size.xUnitsPerInch()).isEqualTo(1440.0);
        assertThat(size.yUnitsPerInch()).isEqualTo(1440.0);
        assertThat(size.width()).isEqualTo(12_240);
        assertThat(size.height()).isEqualTo(15_840);
    }

    @Test
    public void tenCentimetreBaseGivesUnitsPerInch() {
        // 944 units per 10 cm is 944 / (10 / 2.54), about 239.8 units per inch.
        byte[] pgd = {1, 1, 0x03, (byte) 0xB0, 0x03, (byte) 0xB0, 0, 0, 1, 0, 0, 1, 0, 0, 0};

        PageBuilder.PageSize size = PageBuilder.decodePgd(pgd);

        assertThat(size.xUnitsPerInch()).isCloseTo(944 / (10 / 2.54),
                org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    public void unknownUnitBaseIsRefused() {
        byte[] pgd = PageBuilder.pageDescriptor(12_240, 15_840);
        pgd[0] = 7;

        assertThrows(IllegalArgumentException.class, () -> PageBuilder.decodePgd(pgd));
    }

    @Test
    public void findsTheLocalIdOfAMappedFont() {
        assertThat(PageBuilder.codedFontLocalId(PageBuilder.mapCodedFont(3)).getAsInt())
                .isEqualTo(3);
    }

    @Test
    public void mcfWithoutAFontHasNoLocalId() {
        assertThat(PageBuilder.codedFontLocalId(new byte[0]).isEmpty()).isTrue();
    }

    @Test
    public void addedFontTakesAnUnusedLocalId() {
        ByteArrayOutputStream mcf = new ByteArrayOutputStream();
        mcf.writeBytes(PageBuilder.mapCodedFont(1));
        mcf.writeBytes(PageBuilder.mapCodedFont(2));

        PageBuilder.McfWithFont added = PageBuilder.withFontAdded(mcf.toByteArray());

        assertThat(added.localId()).isEqualTo(3);
        assertThat(PageBuilder.codedFontLocalId(added.data()).getAsInt()).isEqualTo(1);
    }

    @Test
    public void ptocaSelectsTheFontThenMovesAndWritesEachRun() {
        byte[] ptoca = PageBuilder.ptoca(1, List.of(new PageBuilder.TextRun(100, 200,
                new byte[] {(byte) 0xC1})));

        // Escape + SCFL to font 1, then AMB (y) and AMI (x) and the TRN with its text.
        assertThat(ptoca).containsSequence((byte) 0x2B, (byte) 0xD3, (byte) 0x03,
                (byte) 0xF1, (byte) 0x01);
        assertThat(ptoca).containsSequence((byte) 0x04, (byte) 0xD3, (byte) 0x00,
                (byte) 0xC8);
        assertThat(ptoca).containsSequence((byte) 0x04, (byte) 0xC7, (byte) 0x00,
                (byte) 0x64, (byte) 0x03, (byte) 0xDA, (byte) 0xC1);
    }

    @Test
    public void ptocaRefusesARunTooLongForOneTrn() {
        assertThrows(IllegalArgumentException.class, () -> PageBuilder.ptoca(1,
                List.of(new PageBuilder.TextRun(0, 0, new byte[PageBuilder.MAX_TRN_TEXT + 1]))));
    }
}
