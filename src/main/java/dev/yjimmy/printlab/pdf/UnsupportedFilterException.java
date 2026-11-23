package dev.yjimmy.printlab.pdf;

/**
 * A stream uses a filter (or filter parameters) that {@link StreamDecoder} doesn't decode. The
 * file is still readable: only this stream's data can't be decoded.
 */
public class UnsupportedFilterException extends RuntimeException {

    private final String filter;

    public UnsupportedFilterException(String filter, String message) {
        super(message);
        this.filter = filter;
    }

    /** The filter's name, without the slash, such as {@code DCTDecode}. */
    public String filter() {
        return filter;
    }
}
