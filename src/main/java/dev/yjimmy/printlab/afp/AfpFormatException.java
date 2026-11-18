package dev.yjimmy.printlab.afp;

/** The bytes at {@link #offset()} aren't a valid MO:DCA structured field. */
public class AfpFormatException extends RuntimeException {

    private final long offset;

    public AfpFormatException(long offset, String message) {
        super(String.format("At byte offset 0x%08X (%d): %s", offset, offset, message));
        this.offset = offset;
    }

    /** Byte offset of the field (its {@code 0x5A}) where the problem was found. */
    public long offset() {
        return offset;
    }
}
