package dev.yjimmy.printlab.steps;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many objects a PDF has, read from its last trailer without loading the file. The number is the
 * best single predictor of the heap PDFBox needs for a file (see {@link HeapLimit}): every object
 * that is reached becomes a Java object, whatever its size on disk, so a 2 MB file of 200,000 blank
 * pages needs far more heap than a 19 MB file that is five images.
 *
 * <p>The count is the {@code /Size} of the newest cross-reference section: in a trailer
 * dictionary (classic table, within the file's last few KB) or in the dictionary of a
 * cross-reference stream (at the offset {@code startxref} gives). It is the highest object number
 * plus one, so a file with many free or unused numbers overstates it. It is only a read of at most
 * two small blocks of the file, never a parse, and gives up quietly (empty) on anything it can't
 * make sense of.
 */
final class PdfSize {

    private static final int TAIL = 4096;
    private static final int DICTIONARY = 2048;
    private static final Pattern SIZE = Pattern.compile("/Size\\s+(\\d{1,12})(?!\\d)");
    private static final Pattern STARTXREF = Pattern.compile("startxref\\s+(\\d{1,15})");

    private PdfSize() {
    }

    /** The file's object count, or empty if it can't be found. */
    static OptionalLong objects(Path file) {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            String tail = read(channel, Math.max(0, size - TAIL), (int) Math.min(size, TAIL));
            int startxref = tail.lastIndexOf("startxref");
            // A classic table is followed by a trailer dictionary, which holds /Size.
            OptionalLong inTrailer = lastSize(startxref >= 0 ? tail.substring(0, startxref) : tail);
            if (inTrailer.isPresent()) {
                return inTrailer;
            }
            // A cross-reference stream has it in the stream's own dictionary, where startxref points.
            if (startxref >= 0) {
                Matcher offset = STARTXREF.matcher(tail.substring(startxref));
                if (offset.find()) {
                    long at = Long.parseLong(offset.group(1));
                    if (at >= 0 && at < size) {
                        String dictionary = read(channel, at, (int) Math.min(DICTIONARY, size - at));
                        Matcher m = SIZE.matcher(dictionary);
                        if (m.find()) {
                            return OptionalLong.of(Long.parseLong(m.group(1)));
                        }
                    }
                }
            }
            return OptionalLong.empty();
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /** The last {@code /Size n} in {@code text}, which is the newest trailer's. */
    private static OptionalLong lastSize(String text) {
        Matcher m = SIZE.matcher(text);
        OptionalLong last = OptionalLong.empty();
        while (m.find()) {
            last = OptionalLong.of(Long.parseLong(m.group(1)));
        }
        return last;
    }

    private static String read(SeekableByteChannel channel, long position, int length)
            throws IOException {
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // keep reading until the block is full or the file ends
        }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.ISO_8859_1);
    }
}
