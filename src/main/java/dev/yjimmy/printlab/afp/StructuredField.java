package dev.yjimmy.printlab.afp;

import java.util.Arrays;

/**
 * One MO:DCA structured field, as {@link AfpReader} found it in a file.
 *
 * <p>On disk a field is (MO:DCA Reference AFPC-0004-11, "MO:DCA Structured Field Syntax"):
 * <pre>
 *   5A        carriage-control byte AFP files put before each field; not part of MO:DCA, and
 *             not counted in the length
 *   LL LL     SFLength, big-endian, 8-32,767: the whole field, counting itself but not the 5A
 *   D3 A8 A8  SFTypeID D3TTCC: class D3, type TT, category CC (this one is BDT, Begin Document)
 *   FF        FlagByte: 80 extension, 20 segmented, 08 padding; other bits reserved
 *   00 00     reserved; should be zero (some AFP applications put a sequence number here)
 *   ...       extension (if flagged: a length byte counting itself, then up to 254 bytes),
 *             data, then padding (if flagged)
 * </pre>
 *
 * @param offset    byte offset of the {@code 0x5A} in the file
 * @param length    SFLength: header (8 bytes) plus extension, data and padding
 * @param typeId    the 3-byte type ID as an int, e.g. {@code 0xD3A8A8}
 * @param flags     the flag byte
 * @param reserved  bytes 6-7, which MO:DCA reserves; often a sequence number in practice
 * @param extension the extension bytes after its own length byte; empty when the flag is off
 * @param data      the field's data: after any extension, without any padding
 */
public record StructuredField(
        long offset, int length, int typeId, int flags, int reserved, byte[] extension,
        byte[] data) {

    /** Flag bit 0 (ExtFlag): an extension follows the header; its first byte is its length. */
    public static final int FLAG_EXTENSION = 0x80;
    /**
     * Flag bit 2 (SegFlag): the data continues in the next structured field, which must follow
     * immediately. The last segment has the flag off. {@link AfpReader} returns each segment as
     * its own field; {@link AfpDump} checks the sequence.
     */
    public static final int FLAG_SEGMENTED = 0x20;
    /** Flag bit 4 (PadFlag): padding follows the data. {@link AfpReader} strips it. */
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

    /** True when the data continues in the next field (see {@link #FLAG_SEGMENTED}). */
    public boolean isSegmented() {
        return (flags & FLAG_SEGMENTED) != 0;
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
                && flags == f.flags && reserved == f.reserved
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
