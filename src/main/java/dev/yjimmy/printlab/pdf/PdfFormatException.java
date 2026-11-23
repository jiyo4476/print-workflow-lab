package dev.yjimmy.printlab.pdf;

/** The bytes at {@link #offset()} aren't valid PDF syntax. */
public class PdfFormatException extends RuntimeException {

    private final long offset;

    public PdfFormatException(long offset, String message) {
        super(String.format("At byte offset 0x%08X (%d): %s", offset, offset, message));
        this.offset = offset;
    }

    /** Byte offset in the file where the problem was found. */
    public long offset() {
        return offset;
    }
}
