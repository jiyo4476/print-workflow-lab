package dev.yjimmy.printlab.pdf;

import dev.yjimmy.printlab.pdf.PdfLexer.Token;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a page's decoded content into {@link Operation}s (ISO 32000-1, 7.8.2 and 8.9.7).
 *
 * <p>Content is postfix: operands come first and the operator after them, so
 * {@code 72 752 Td} is the operator {@code Td} with the operands 72 and 752. Operands are the
 * same objects as in the rest of the file (numbers, names, strings, arrays, dictionaries,
 * booleans, null); any other keyword is an operator. Operators aren't checked against the list
 * in Annex A, so an unknown one is returned like any other and left to the caller.
 *
 * <p><b>Inline images</b> are the one place where content isn't made of tokens. After
 * {@code BI key value ... ID} and one white-space byte come the image's raw bytes, then
 * {@code EI}. Those bytes can contain anything, including {@code EI}, so the end is found in
 * this order:
 * <ol>
 *   <li>a declared length, {@code /L} or {@code /Length} (PDF 2.0);</li>
 *   <li>for an unfiltered image, the size its parameters give:
 *       {@code ceil(W × components × BPC / 8) × H}, the components coming from {@code /CS}
 *       ({@code /G}, {@code /RGB}, {@code /CMYK}, or an {@code /I} indexed array) or 1 for an
 *       {@code /IM true} image mask;</li>
 *   <li>otherwise, the first {@code EI} that has white space before it, white space or the end
 *       after it, and then only text (printable ASCII and white space) for the next
 *       {@value #LOOK_AHEAD} bytes, which is what content looks like and compressed image data
 *       doesn't.</li>
 * </ol>
 *
 * <p>Errors are {@link PdfFormatException}s with the offset in the decoded content.
 */
public final class ContentStreamParser {

    /** How many bytes after a candidate {@code EI} must look like content. */
    static final int LOOK_AHEAD = 32;

    private final byte[] data;
    private final PdfLexer lexer;
    /** Reads array and dictionary operands; it shares {@link #data} but not the position. */
    private final PdfParser parser;

    public ContentStreamParser(byte[] content) {
        this.data = content;
        this.lexer = new PdfLexer(content);
        this.parser = new PdfParser(content);
    }

    /** All the operations in {@code content}, in order. */
    public static List<Operation> parse(byte[] content) {
        ContentStreamParser parser = new ContentStreamParser(content);
        List<Operation> operations = new ArrayList<>();
        for (Operation op = parser.next(); op != null; op = parser.next()) {
            operations.add(op);
        }
        return operations;
    }

    /** The next operation, or null at the end of the content. */
    public Operation next() {
        List<PdfObject> operands = new ArrayList<>();
        int start = -1;
        while (true) {
            Token token = lexer.next();
            if (start < 0) {
                start = token.offset();
            }
            switch (token.kind()) {
                case END_OF_FILE -> {
                    if (operands.isEmpty()) {
                        return null;
                    }
                    throw new PdfFormatException(start, operands.size()
                            + " operand(s) at the end of the content with no operator after them");
                }
                case KEYWORD -> {
                    PdfObject constant = constant(token.keyword());
                    if (constant != null) {
                        operands.add(constant);
                        break;
                    }
                    switch (token.keyword()) {
                        case "BI" -> {
                            if (!operands.isEmpty()) {
                                throw new PdfFormatException(start, "operands before 'BI' (an inline image takes none)");
                            }
                            return inlineImage(token);
                        }
                        case "ID", "EI" -> throw new PdfFormatException(token.offset(),
                                "'" + token.keyword() + "' outside an inline image (no 'BI' before it)");
                        case "{", "}" -> throw new PdfFormatException(token.offset(),
                                "'" + token.keyword() + "' in content (braces are only for PostScript functions)");
                        default -> {
                            return new Operation(token.keyword(), operands, start);
                        }
                    }
                }
                default -> operands.add(operand(token));
            }
        }
    }

    private static PdfObject constant(String keyword) {
        return switch (keyword) {
            case "true" -> PdfBoolean.TRUE;
            case "false" -> PdfBoolean.FALSE;
            case "null" -> PdfNull.INSTANCE;
            default -> null;
        };
    }

    /** A value, or the array or dictionary that starts with {@code token}. */
    private PdfObject operand(Token token) {
        switch (token.kind()) {
            case VALUE -> {
                return token.value();
            }
            case ARRAY_START, DICTIONARY_START -> {
                parser.seek(token.offset());
                PdfObject object = parser.parseObject();
                lexer.seek(parser.position());
                return object;
            }
            case KEYWORD -> {
                PdfObject constant = constant(token.keyword());
                if (constant != null) {
                    return constant;
                }
                throw new PdfFormatException(token.offset(), "expected a value, found '" + token + "'");
            }
            case END_OF_FILE -> throw new PdfFormatException(token.offset(),
                    "expected a value, found the end of the content");
            default -> throw new PdfFormatException(token.offset(), "'" + token + "' with nothing open");
        }
    }

    /** {@code BI} has been read: the parameters up to {@code ID}, the data, and {@code EI}. */
    private Operation inlineImage(Token bi) {
        Map<String, PdfObject> entries = new LinkedHashMap<>();
        while (true) {
            Token key = lexer.next();
            if (key.isKeyword("ID")) {
                break;
            }
            if (key.kind() == PdfLexer.Kind.END_OF_FILE) {
                throw new PdfFormatException(bi.offset(), "inline image has no 'ID'");
            }
            if (!(key.value() instanceof PdfName name)) {
                throw new PdfFormatException(key.offset(), "inline image key must be a name, found '" + key + "'");
            }
            Token value = lexer.next();
            if (value.isKeyword("ID")) {
                throw new PdfFormatException(value.offset(), "inline image key " + name + " has no value");
            }
            entries.put(name.name(), operand(value));
        }
        PdfDictionary parameters = new PdfDictionary(entries);
        int afterId = lexer.position();
        if (afterId >= data.length || !PdfLexer.isWhiteSpace(data[afterId] & 0xFF)) {
            throw new PdfFormatException(afterId, "'ID' must be followed by one white-space byte");
        }
        int dataStart = afterId + 1;
        int length = declaredLength(parameters, dataStart);
        if (length < 0 && !parameters.entries().containsKey("F") && !parameters.entries().containsKey("Filter")) {
            length = unfilteredSize(parameters);
        }
        int dataEnd;
        if (length >= 0) {
            if (length > data.length - dataStart) {
                throw new PdfFormatException(dataStart, "inline image needs " + length + " bytes of data, but only "
                        + (data.length - dataStart) + " are left");
            }
            dataEnd = dataStart + length;
            lexer.seek(dataEnd);
            Token ei = lexer.next();
            if (!ei.isKeyword("EI")) {
                throw new PdfFormatException(ei.offset(), "expected 'EI' after the " + length
                        + " bytes of inline image data, found '" + ei + "'");
            }
        } else {
            int ei = findEi(dataStart, bi.offset());
            dataEnd = Math.max(dataStart, ei - 1);
            lexer.seek(ei + 2);
        }
        PdfStream image = new PdfStream(parameters, Arrays.copyOfRange(data, dataStart, dataEnd), dataStart);
        return new Operation("BI", List.of(image), bi.offset());
    }

    /** {@code /L} or {@code /Length}, or -1 if neither is given. */
    private static int declaredLength(PdfDictionary parameters, int at) {
        PdfObject length = parameters.get("L") != null ? parameters.get("L") : parameters.get("Length");
        if (length == null) {
            return -1;
        }
        if (length instanceof PdfNumber n && n.isInt() && n.intValue() >= 0) {
            return n.intValue();
        }
        throw new PdfFormatException(at, "inline image length must be a whole number, found " + length);
    }

    /**
     * The data size an unfiltered image's parameters give, or -1 if they don't give one (a
     * named color space from the page resources, or missing or odd values).
     */
    static int unfilteredSize(PdfDictionary parameters) {
        int width = intValue(parameters, "W", "Width");
        int height = intValue(parameters, "H", "Height");
        int bits;
        int components;
        if (first(parameters, "IM", "ImageMask") == PdfBoolean.TRUE) {
            bits = 1;
            components = 1;
        } else {
            bits = intValue(parameters, "BPC", "BitsPerComponent");
            components = components(first(parameters, "CS", "ColorSpace"));
        }
        if (width <= 0 || height <= 0 || components <= 0 || !List.of(1, 2, 4, 8, 16).contains(bits)) {
            return -1;
        }
        long size = ((long) width * components * bits + 7) / 8 * height;
        return size <= Integer.MAX_VALUE ? (int) size : -1;
    }

    private static PdfObject first(PdfDictionary parameters, String abbreviation, String name) {
        PdfObject value = parameters.get(abbreviation);
        return value != null ? value : parameters.get(name);
    }

    private static int intValue(PdfDictionary parameters, String abbreviation, String name) {
        return first(parameters, abbreviation, name) instanceof PdfNumber n && n.isInt() ? n.intValue() : -1;
    }

    private static int components(PdfObject colorSpace) {
        if (colorSpace instanceof PdfName name) {
            return switch (name.name()) {
                case "G", "DeviceGray" -> 1;
                case "RGB", "DeviceRGB" -> 3;
                case "CMYK", "DeviceCMYK" -> 4;
                default -> -1;
            };
        }
        if (colorSpace instanceof PdfArray array && array.size() > 0 && array.get(0) instanceof PdfName name
                && (name.name().equals("I") || name.name().equals("Indexed"))) {
            return 1;
        }
        return -1;
    }

    /** Where the {@code EI} that ends the image data starts. */
    private int findEi(int from, int biOffset) {
        for (int i = from; i + 1 < data.length; i++) {
            if (data[i] == 'E' && data[i + 1] == 'I'
                    && PdfLexer.isWhiteSpace(data[i - 1] & 0xFF)
                    && (i + 2 == data.length || PdfLexer.isWhiteSpace(data[i + 2] & 0xFF))
                    && looksLikeContent(i + 2)) {
                return i;
            }
        }
        throw new PdfFormatException(biOffset, "inline image has no 'EI' after its data");
    }

    private boolean looksLikeContent(int from) {
        int end = Math.min(data.length, from + LOOK_AHEAD);
        for (int i = from; i < end; i++) {
            int b = data[i] & 0xFF;
            boolean text = (b >= 0x20 && b <= 0x7E) || b == '\t' || b == '\n' || b == '\r' || b == '\f';
            if (!text) {
                return false;
            }
        }
        return true;
    }
}
