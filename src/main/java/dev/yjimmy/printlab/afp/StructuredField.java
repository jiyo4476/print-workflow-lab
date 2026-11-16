package dev.yjimmy.printlab.afp;

import java.util.Arrays;

/**
 * One MO:DCA structured field, as {@link AfpReader} found it in a file.
 *
 * <p>On disk a field is:
 * <pre>
 *   5A        introducer (not counted in the length)
 *   LL LL     length, big-endian: counts itself and everything after it, but not the 5A
 *   D3 A8 A8  type ID: class, type, category (this one is BDT, Begin Document)
 *   FF        flags (bit 0x80: an extension follows the header)
 *   SS SS     sequence number (often 0000)
 *   ...       data: length - 8 bytes
 * </pre>
 *
 * @param offset   byte offset of the {@code 0x5A} introducer in the file
 * @param length   the field's length value: header (8 bytes) plus extension plus data
 * @param typeId   the 3-byte type ID as an int, e.g. {@code 0xD3A8A8}
 * @param flags    the flags byte
 * @param sequence the 2-byte sequence number
 * @param extension the extension bytes after its own length byte; empty when the flag is off
 * @param data     the field's data, after any extension
 */
public record StructuredField(
        long offset, int length, int typeId, int flags, int sequence, byte[] extension,
        byte[] data) {

    /** Flag bit: an extension follows the 8-byte header; its first byte is its own length. */
    public static final int FLAG_EXTENSION = 0x80;
    /** Flag bit: the data continues in the next field (segmented). Reported, not joined. */
    public static final int FLAG_SEGMENTED = 0x20;
    /** Flag bit: the data ends with padding. Reported, not stripped. */
    public static final int FLAG_PADDING = 0x08;

    private static final int TYPE_BEGIN = 0xA8;
    private static final int TYPE_END = 0xA9;

    public StructuredField {
        extension = extension.clone();
        data = data.clone();
    }

    @Override
    public byte[] extension() {
        return extension.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    /** The field type, or {@code null} for an ID {@link FieldType} doesn't know. */
    public FieldType type() {
        return FieldType.forId(typeId).orElse(null);
    }

    /** The short name ({@code BNG}), or the ID in hex ({@code D3EEFF}) when unknown. */
    public String typeName() {
        return FieldType.nameOf(typeId);
    }

    /** True for Begin fields (type byte {@code A8}): BDT, BNG, BPG, BAG, ... */
    public boolean isBegin() {
        return typeByte() == TYPE_BEGIN;
    }

    /** True for End fields (type byte {@code A9}): EDT, ENG, EPG, EAG, ... */
    public boolean isEnd() {
        return typeByte() == TYPE_END;
    }

    /**
     * For a Begin field, the ID of the End field that closes it: the same class and category with
     * type {@code A9} (BNG {@code D3A8AD} → ENG {@code D3A9AD}).
     */
    public int matchingEndId() {
        if (!isBegin()) {
            throw new IllegalStateException(typeName() + " is not a Begin field");
        }
        return (typeId & 0xFF00FF) | (TYPE_END << 8);
    }

    private int typeByte() {
        return (typeId >> 8) & 0xFF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StructuredField f
                && offset == f.offset && length == f.length && typeId == f.typeId
                && flags == f.flags && sequence == f.sequence
                && Arrays.equals(extension, f.extension) && Arrays.equals(data, f.data);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(offset) + typeId;
    }

    @Override
    public String toString() {
        return String.format("%s at 0x%08X (length %d, %d data bytes)",
                typeName(), offset, length, data.length);
    }
}
