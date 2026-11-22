package dev.yjimmy.printlab.pdf;

import dev.yjimmy.printlab.pdf.PdfLexer.Kind;
import dev.yjimmy.printlab.pdf.PdfLexer.Token;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds {@link PdfObject}s from {@link PdfLexer} tokens (ISO 32000-1, 7.3).
 *
 * <ul>
 *   <li>{@link #parseObject()} reads one direct object. {@code 12 0 R} is a
 *       {@link PdfReference}; {@code 12 0} without the {@code R} is two numbers, so the parser
 *       looks up to two tokens ahead after an integer.</li>
 *   <li>{@link #parseIndirectObject()} reads {@code 12 0 obj ... endobj}, including a stream's
 *       {@code stream ... endstream} data. The stream's {@code /Length} may itself be a reference
 *       ({@code /Length 13 0 R}), which writers use when they don't know the length until the
 *       data is written; the {@code resolver} given to the constructor looks it up.</li>
 * </ul>
 *
 * <p>Every error is a {@link PdfFormatException} with the byte offset where it was found.
 */
public final class PdfParser {

    private final PdfLexer lexer;
    private final Function<PdfReference, PdfObject> resolver;
    /** Tokens read ahead to tell a reference from two numbers, next one first. */
    private final Deque<Token> pushedBack = new ArrayDeque<>();

    /** A parser that can't resolve an indirect {@code /Length}. */
    public PdfParser(byte[] data) {
        this(data, ref -> {
            throw new IllegalStateException("no cross-reference table to resolve " + ref);
        });
    }

    /**
     * @param resolver returns the object a reference points to; used for a stream's indirect
     *     {@code /Length}
     */
    public PdfParser(byte[] data, Function<PdfReference, PdfObject> resolver) {
        this.lexer = new PdfLexer(data);
        this.resolver = resolver;
    }

    /** Where the next token is read from. */
    public int position() {
        return pushedBack.isEmpty() ? lexer.position() : pushedBack.peekFirst().offset();
    }

    public void seek(int offset) {
        pushedBack.clear();
        lexer.seek(offset);
    }

    private Token next() {
        return pushedBack.isEmpty() ? lexer.next() : pushedBack.removeFirst();
    }

    private void pushBack(Token token) {
        pushedBack.addFirst(token);
    }

    /** Reads one direct object: a value, array or dictionary, or a reference. */
    public PdfObject parseObject() {
        return parseObject(next());
    }

    private PdfObject parseObject(Token token) {
        return switch (token.kind()) {
            case VALUE -> token.value() instanceof PdfNumber number ? numberOrReference(number, token)
                    : token.value();
            case ARRAY_START -> array(token);
            case DICTIONARY_START -> dictionary(token);
            case KEYWORD -> switch (token.keyword()) {
                case "true" -> PdfBoolean.TRUE;
                case "false" -> PdfBoolean.FALSE;
                case "null" -> PdfNull.INSTANCE;
                default -> throw new PdfFormatException(token.offset(),
                        "expected an object, found the keyword '" + token.keyword() + "'");
            };
            case ARRAY_END, DICTIONARY_END -> throw new PdfFormatException(token.offset(),
                    "expected an object, found '" + token + "'");
            case END_OF_FILE -> throw new PdfFormatException(token.offset(),
                    "expected an object, found the end of the file");
        };
    }

    /** {@code n g R} is a reference; otherwise {@code n} is just a number. */
    private PdfObject numberOrReference(PdfNumber number, Token token) {
        if (!isObjectNumber(number)) {
            return number;
        }
        Token second = next();
        if (second.value() instanceof PdfNumber generation && isObjectNumber(generation)) {
            Token third = next();
            if (third.isKeyword("R")) {
                return new PdfReference(number.intValue(), generation.intValue());
            }
            pushBack(third);
        }
        pushBack(second);
        return number;
    }

    private static boolean isObjectNumber(PdfNumber number) {
        return number.isInt() && number.intValue() >= 0;
    }

    private PdfArray array(Token start) {
        List<PdfObject> items = new ArrayList<>();
        while (true) {
            Token token = next();
            if (token.kind() == Kind.ARRAY_END) {
                return new PdfArray(items);
            }
            if (token.kind() == Kind.END_OF_FILE) {
                throw new PdfFormatException(token.offset(),
                        "array opened at offset " + start.offset() + " is not closed with ']'");
            }
            items.add(parseObject(token));
        }
    }

    private PdfDictionary dictionary(Token start) {
        Map<String, PdfObject> entries = new LinkedHashMap<>();
        while (true) {
            Token key = next();
            if (key.kind() == Kind.DICTIONARY_END) {
                return new PdfDictionary(entries);
            }
            if (key.kind() == Kind.END_OF_FILE) {
                throw new PdfFormatException(key.offset(),
                        "dictionary opened at offset " + start.offset() + " is not closed with '>>'");
            }
            if (!(key.value() instanceof PdfName name)) {
                throw new PdfFormatException(key.offset(),
                        "dictionary key must be a name, found '" + key + "'");
            }
            Token value = next();
            if (value.kind() == Kind.DICTIONARY_END) {
                throw new PdfFormatException(value.offset(), "dictionary key " + name + " has no value");
            }
            // A repeated key keeps its last value.
            entries.put(name.name(), parseObject(value));
        }
    }

    /**
     * Reads {@code n g obj <object> endobj} at the current position, where the object may be a
     * stream.
     */
    public PdfIndirectObject parseIndirectObject() {
        Token number = next();
        Token generation = next();
        Token obj = next();
        if (!(number.value() instanceof PdfNumber n && isObjectNumber(n)
                && generation.value() instanceof PdfNumber g && isObjectNumber(g)
                && obj.isKeyword("obj"))) {
            throw new PdfFormatException(number.offset(),
                    "expected 'n g obj', found '" + number + " " + generation + " " + obj + "'");
        }
        PdfReference reference = new PdfReference(n.intValue(), g.intValue());
        PdfObject object = parseObject();
        Token after = next();
        if (after.isKeyword("stream")) {
            if (!(object instanceof PdfDictionary dictionary)) {
                throw new PdfFormatException(after.offset(), "'stream' must follow a dictionary");
            }
            object = streamData(reference, dictionary, after);
            after = next();
        }
        if (!after.isKeyword("endobj")) {
            throw new PdfFormatException(after.offset(),
                    "object " + reference + " expected 'endobj', found '" + after + "'");
        }
        return new PdfIndirectObject(reference, object, number.offset());
    }

    /**
     * The bytes after {@code stream}: its end of line (CR LF or LF; a lone CR is accepted too),
     * then exactly {@code /Length} bytes, then {@code endstream}.
     */
    private PdfStream streamData(PdfReference owner, PdfDictionary dictionary, Token stream) {
        byte[] data = lexer.data();
        int start = stream.offset() + "stream".length();
        if (start < data.length && data[start] == '\r') {
            start++;
        }
        if (start < data.length && data[start] == '\n') {
            start++;
        }
        int length = length(owner, dictionary, stream);
        if (length > data.length - start) {
            throw new PdfFormatException(start, "stream of object " + owner + " has /Length " + length
                    + " but only " + (data.length - start) + " bytes are left in the file");
        }
        seek(start + length);
        Token end = next();
        if (!end.isKeyword("endstream")) {
            throw new PdfFormatException(end.offset(), "stream of object " + owner
                    + " expected 'endstream' after /Length " + length + " bytes, found '" + end + "'");
        }
        return new PdfStream(dictionary, Arrays.copyOfRange(data, start, start + length), start);
    }

    private int length(PdfReference owner, PdfDictionary dictionary, Token stream) {
        PdfObject length = dictionary.get("Length");
        if (length instanceof PdfReference ref) {
            int resumeAt = position();
            try {
                length = resolver.apply(ref);
            } catch (RuntimeException e) {
                throw new PdfFormatException(stream.offset(), "stream of object " + owner
                        + " has /Length " + ref + ", which can't be resolved: " + e.getMessage());
            }
            seek(resumeAt);
        }
        if (length instanceof PdfNumber n && isObjectNumber(n)) {
            return n.intValue();
        }
        throw new PdfFormatException(stream.offset(), "stream of object " + owner
                + " needs a non-negative integer /Length, found " + length);
    }
}
