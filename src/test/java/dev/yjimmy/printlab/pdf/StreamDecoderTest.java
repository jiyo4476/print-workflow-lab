package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.Deflater;
import org.junit.Test;

public class StreamDecoderTest {

    private static final byte[] CONTENT = "BT /F1 12 Tf 72 712 Td (Hello) Tj ET\n".repeat(50)
            .getBytes(StandardCharsets.ISO_8859_1);

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static PdfStream stream(byte[] data, Object... dictionary) {
        return new PdfStream(PdfDictionary.of(dictionary), data, 100);
    }

    @Test
    public void flateRoundTrip() {
        byte[] compressed = deflate(CONTENT);

        byte[] decoded = new StreamDecoder().decode(stream(compressed, "Filter", PdfName.of("FlateDecode")));

        assertThat(decoded).isEqualTo(CONTENT);
        assertThat(compressed.length).as("compressed").isLessThan(CONTENT.length);
    }

    @Test
    public void unfilteredStreamIsItsData() {
        StreamDecoder decoder = new StreamDecoder();
        PdfStream stream = stream(CONTENT);

        assertThat(decoder.decode(stream)).isEqualTo(CONTENT);
        assertThat(decoder.filters(stream)).isEmpty();
    }

    @Test
    public void filterArrayIsAppliedInOrder() {
        PdfStream twice = stream(deflate(deflate(CONTENT)),
                "Filter", PdfArray.of(PdfName.of("FlateDecode"), PdfName.of("FlateDecode")),
                "DecodeParms", PdfArray.of(PdfNull.INSTANCE, PdfDictionary.of("Predictor", PdfNumber.of(1))));

        assertThat(new StreamDecoder().decode(twice)).isEqualTo(CONTENT);
    }

    @Test
    public void unknownFilterReportedAsUnsupported() {
        PdfStream jpeg = stream(new byte[] {(byte) 0xFF, (byte) 0xD8}, "Filter", PdfName.of("DCTDecode"));

        UnsupportedFilterException e = assertThrows(UnsupportedFilterException.class,
                () -> new StreamDecoder().decode(jpeg));

        assertEquals("filter", "DCTDecode", e.filter());
        assertEquals("message", "/DCTDecode is not supported; only /FlateDecode is decoded", e.getMessage());
        assertThat(new StreamDecoder().filters(jpeg)).containsExactly("DCTDecode");
    }

    @Test
    public void flateWithAPredictorIsReportedAsUnsupported() {
        PdfStream png = stream(deflate(CONTENT), "Filter", PdfName.of("FlateDecode"),
                "DecodeParms", PdfDictionary.of("Predictor", PdfNumber.of(12), "Columns", PdfNumber.of(4)));

        UnsupportedFilterException e = assertThrows(UnsupportedFilterException.class,
                () -> new StreamDecoder().decode(png));

        assertThat(e.getMessage()).contains("/FlateDecode with /Predictor 12 is not supported");
    }

    @Test
    public void indirectFilterAndParametersAreResolved() {
        Map<PdfReference, PdfObject> objects = Map.of(
                new PdfReference(5, 0), PdfName.of("FlateDecode"),
                new PdfReference(6, 0), PdfDictionary.of("Predictor", new PdfReference(7, 0)),
                new PdfReference(7, 0), PdfNumber.of(1));
        StreamDecoder decoder = new StreamDecoder(objects::get, StreamDecoder.DEFAULT_MAX_DECODED_BYTES);

        byte[] decoded = decoder.decode(stream(deflate(CONTENT),
                "Filter", PdfArray.of(new PdfReference(5, 0)), "DecodeParms", new PdfReference(6, 0)));

        assertThat(decoded).isEqualTo(CONTENT);
    }

    @Test
    public void badFilterEntriesFail() {
        StreamDecoder decoder = new StreamDecoder();

        assertThat(assertThrows(PdfFormatException.class,
                () -> decoder.decode(stream(CONTENT, "Filter", PdfNumber.of(3)))).getMessage())
                .contains("/Filter must be a name or an array of names, found 3");
        assertThat(assertThrows(PdfFormatException.class,
                () -> decoder.decode(stream(CONTENT, "Filter", PdfArray.of(PdfNumber.of(3))))).getMessage())
                .contains("/Filter array must hold names, found 3");
    }

    @Test
    public void corruptFlateDataFailsWithTheStreamOffset() {
        byte[] corrupt = deflate(CONTENT);
        corrupt[0] = 0x00; // not a zlib header

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> new StreamDecoder().decode(stream(corrupt, "Filter", PdfName.of("FlateDecode"))));

        assertThat(e.getMessage()).contains("bad Flate data");
        assertEquals("offset", 100, e.offset());
    }

    @Test
    public void truncatedFlateDataFails() {
        byte[] compressed = deflate(CONTENT);
        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> new StreamDecoder().decode(stream(truncated, "Filter", PdfName.of("FlateDecode"))));

        assertThat(e.getMessage()).contains("Flate data ends before the compressed stream does");
    }

    @Test
    public void decodingStopsPastTheSizeLimit() {
        byte[] zeros = deflate(new byte[100_000]);
        StreamDecoder decoder = new StreamDecoder(ref -> PdfNull.INSTANCE, 10_000);

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> decoder.decode(stream(zeros, "Filter", PdfName.of("FlateDecode"))));

        assertThat(e.getMessage()).contains("stream decodes to more than 10000 bytes");
        assertThat(zeros.length).as("a small stream").isLessThan(1_000);
    }

    @Test
    public void withoutAResolverAnIndirectFilterFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StreamDecoder().decode(stream(CONTENT, "Filter", new PdfReference(5, 0))));

        assertThat(e.getMessage()).contains("no cross-reference table to resolve 5 0 R");
    }
}
