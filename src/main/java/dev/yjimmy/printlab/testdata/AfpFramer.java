package dev.yjimmy.printlab.testdata;

import dev.yjimmy.printlab.afp.AfpFraming;
import dev.yjimmy.printlab.afp.AfpReader;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites an AFP file in the form other tools write, so the readers can be tried on files
 * shaped like production ones: line endings or zero padding between fields, record descriptor
 * words, or blocks of records. The inverse of what {@link AfpFraming} lets the reader accept.
 */
public final class AfpFramer {

    /** The longest block a variable-blocked data set may have (z/OS limit). */
    static final int MAX_BLOCK = 32_760;
    private static final int DESCRIPTOR = 4;

    private AfpFramer() {
    }

    /**
     * {@code strict} (the output of the AFP writer: {@code 0x5A} and a field, one after another)
     * written as {@code framing}. {@link AfpFraming#STRICT} and {@link AfpFraming#AUTO} return it
     * unchanged; {@link AfpFraming#PADDED} puts CR LF after each field and zero bytes at the end,
     * up to a multiple of 16.
     */
    public static byte[] frame(byte[] strict, AfpFraming framing) {
        return frame(strict, framing, MAX_BLOCK);
    }

    /**
     * As {@link #frame(byte[], AfpFraming)}, with BDW blocks of at most {@code maxBlock} bytes
     * (up to 65,535, which the reader accepts, though z/OS stops at 32,760). Different block
     * sizes give different first block lengths, which {@link AfpFraming#AUTO} has to tell apart
     * from other forms.
     */
    public static byte[] frame(byte[] strict, AfpFraming framing, int maxBlock) {
        if (maxBlock < 64 || maxBlock > 65_535) {
            throw new IllegalArgumentException("maxBlock must be from 64 to 65535, not " + maxBlock);
        }
        List<byte[]> records = records(strict);
        ByteArrayOutputStream out = new ByteArrayOutputStream(strict.length + records.size() * 8);
        switch (framing) {
            case PADDED -> {
                records.forEach(record -> {
                    out.writeBytes(record);
                    out.write(0x0D);
                    out.write(0x0A);
                });
                while (out.size() % 16 != 0) {
                    out.write(0x00);
                }
            }
            case RDW -> records.forEach(record -> {
                descriptor(out, record.length + DESCRIPTOR);
                out.writeBytes(record);
            });
            case BDW -> {
                List<byte[]> block = new ArrayList<>();
                int size = DESCRIPTOR;
                for (byte[] record : records) {
                    if (size + DESCRIPTOR + record.length > maxBlock && !block.isEmpty()) {
                        writeBlock(out, block, size);
                        block.clear();
                        size = DESCRIPTOR;
                    }
                    block.add(record);
                    size += DESCRIPTOR + record.length;
                }
                if (!block.isEmpty()) {
                    writeBlock(out, block, size);
                }
            }
            default -> out.writeBytes(strict);
        }
        return out.toByteArray();
    }

    private static void writeBlock(ByteArrayOutputStream out, List<byte[]> block, int size) {
        descriptor(out, size);
        for (byte[] record : block) {
            descriptor(out, record.length + DESCRIPTOR);
            out.writeBytes(record);
        }
    }

    private static void descriptor(ByteArrayOutputStream out, int length) {
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(0);
        out.write(0);
    }

    /** Each {@code 0x5A} and field of a strict file. */
    private static List<byte[]> records(byte[] strict) {
        List<byte[]> records = new ArrayList<>();
        int at = 0;
        while (at < strict.length) {
            if ((strict[at] & 0xFF) != AfpReader.INTRODUCER || at + 3 > strict.length) {
                throw new IllegalArgumentException("not a strict AFP file at byte " + at);
            }
            int length = ((strict[at + 1] & 0xFF) << 8) | (strict[at + 2] & 0xFF);
            if (at + 1 + length > strict.length) {
                throw new IllegalArgumentException("truncated field at byte " + at);
            }
            records.add(java.util.Arrays.copyOfRange(strict, at, at + 1 + length));
            at += 1 + length;
        }
        return records;
    }
}
