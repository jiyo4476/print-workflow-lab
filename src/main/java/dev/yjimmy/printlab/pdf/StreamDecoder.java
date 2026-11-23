package dev.yjimmy.printlab.pdf;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes a stream's data through the filters in its {@code /Filter} entry (ISO 32000-1, 7.4).
 *
 * <ul>
 *   <li>No {@code /Filter}: the data is returned as it is.</li>
 *   <li>{@code /FlateDecode} (zlib, RFC 1950) is decoded with {@link Inflater}. An array of
 *       filters is applied in order.</li>
 *   <li>Any other filter, or Flate with a {@code /Predictor} (used by xref streams and images,
 *       PR 8), throws {@link UnsupportedFilterException}: the stream can't be decoded, but the
 *       rest of the file is still readable.</li>
 * </ul>
 *
 * <p>Decoding stops with a {@link PdfFormatException} past {@code maxDecodedBytes}, so a small
 * stream that inflates to gigabytes can't exhaust memory.
 */
public final class StreamDecoder {

    /** The default limit on a decoded stream's size: 256 MiB. */
    public static final int DEFAULT_MAX_DECODED_BYTES = 256 * 1024 * 1024;

    private final Function<PdfReference, PdfObject> resolver;
    private final int maxDecodedBytes;

    /** A decoder for streams whose {@code /Filter} and {@code /DecodeParms} are direct objects. */
    public StreamDecoder() {
        this(ref -> {
            throw new IllegalStateException("no cross-reference table to resolve " + ref);
        }, DEFAULT_MAX_DECODED_BYTES);
    }

    /**
     * @param resolver looks up a {@code /Filter} or {@code /DecodeParms} that is a reference
     * @param maxDecodedBytes the most bytes one stream may decode to
     */
    public StreamDecoder(Function<PdfReference, PdfObject> resolver, int maxDecodedBytes) {
        this.resolver = resolver;
        this.maxDecodedBytes = maxDecodedBytes;
    }

    /** The stream's filter names, without slashes, in the order they're applied; empty if none. */
    public List<String> filters(PdfStream stream) {
        PdfObject filter = resolve(stream.dictionary().get("Filter"));
        List<String> names = new ArrayList<>();
        if (filter instanceof PdfName name) {
            names.add(name.name());
        } else if (filter instanceof PdfArray array) {
            for (PdfObject item : array.items()) {
                if (!(resolve(item) instanceof PdfName name)) {
                    throw new PdfFormatException(stream.dataOffset(),
                            "/Filter array must hold names, found " + item);
                }
                names.add(name.name());
            }
        } else if (filter != null) {
            throw new PdfFormatException(stream.dataOffset(),
                    "/Filter must be a name or an array of names, found " + filter);
        }
        return names;
    }

    /**
     * The stream's data with every filter undone.
     *
     * @throws UnsupportedFilterException if a filter isn't one this decoder knows
     * @throws PdfFormatException if the data is corrupt or decodes past the size limit
     */
    public byte[] decode(PdfStream stream) {
        List<String> filters = filters(stream);
        byte[] data = stream.data();
        for (int i = 0; i < filters.size(); i++) {
            String filter = filters.get(i);
            if (!filter.equals("FlateDecode")) {
                throw new UnsupportedFilterException(filter,
                        "/" + filter + " is not supported; only /FlateDecode is decoded");
            }
            int predictor = predictor(stream, i);
            if (predictor > 1) {
                throw new UnsupportedFilterException(filter, "/FlateDecode with /Predictor "
                        + predictor + " is not supported (PR 8 adds predictors)");
            }
            data = inflate(data, stream.dataOffset());
        }
        return data;
    }

    /** The {@code /Predictor} in the parameters for filter {@code index}, or 1 (none). */
    private int predictor(PdfStream stream, int index) {
        PdfObject parameters = resolve(stream.dictionary().get("DecodeParms"));
        if (parameters instanceof PdfArray array) {
            parameters = index < array.size() ? resolve(array.get(index)) : null;
        }
        if (parameters instanceof PdfDictionary dictionary
                && resolve(dictionary.get("Predictor")) instanceof PdfNumber predictor) {
            return predictor.isInt() ? predictor.intValue() : Integer.MAX_VALUE;
        }
        return 1;
    }

    private PdfObject resolve(PdfObject object) {
        return object instanceof PdfReference ref ? resolver.apply(ref) : object;
    }

    private byte[] inflate(byte[] compressed, long offset) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 3));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new PdfFormatException(offset, "Flate data ends before the compressed stream"
                            + " does (" + compressed.length + " bytes, " + out.size() + " decoded)");
                }
                if (n > maxDecodedBytes - out.size()) {
                    throw new PdfFormatException(offset,
                            "stream decodes to more than " + maxDecodedBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new PdfFormatException(offset, "bad Flate data: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
