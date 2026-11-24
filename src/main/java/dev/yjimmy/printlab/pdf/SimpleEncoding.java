package dev.yjimmy.printlab.pdf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple font's encoding: which glyph name each one-byte character code stands for
 * (ISO 32000-1, 9.6.6), and the Unicode text of each glyph name.
 *
 * <p>The two base encodings come from {@code encodings.txt}, a copy of Annex D's table with
 * the Adobe Glyph List's Unicode values for each name. A font's {@code /Differences} array
 * replaces some codes' names. A name the table doesn't know is read in the {@code uniXXXX}
 * (one or more four-digit code units) or {@code uXXXX} to {@code uXXXXXX} form, or else is
 * U+FFFD (the replacement character), never a guess.
 */
public final class SimpleEncoding {

    /** Shown for a glyph name with no known Unicode value. */
    public static final String UNKNOWN = "\uFFFD";

    public static final SimpleEncoding WIN_ANSI;
    public static final SimpleEncoding STANDARD;
    private static final Map<String, String> UNICODE = new HashMap<>();

    static {
        String[] win = new String[256];
        String[] standard = new String[256];
        try (InputStream in = SimpleEncoding.class.getResourceAsStream("encodings.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(" ");
                if (fields[0].equals("=")) {
                    StringBuilder text = new StringBuilder();
                    for (String hex : fields[2].split(",")) {
                        text.appendCodePoint(Integer.parseInt(hex, 16));
                    }
                    UNICODE.put(fields[1], text.toString());
                } else {
                    int code = Integer.parseInt(fields[0], 16);
                    win[code] = fields[1].equals("-") ? null : fields[1];
                    standard[code] = fields[2].equals("-") ? null : fields[2];
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        WIN_ANSI = new SimpleEncoding("WinAnsiEncoding", win);
        STANDARD = new SimpleEncoding("StandardEncoding", standard);
    }

    private final String name;
    private final String[] names;

    private SimpleEncoding(String name, String[] names) {
        this.name = name;
        this.names = names;
    }

    /** The base encoding called {@code name}, or null if it isn't one of the two supported. */
    public static SimpleEncoding named(String name) {
        return switch (name) {
            case "WinAnsiEncoding" -> WIN_ANSI;
            case "StandardEncoding" -> STANDARD;
            default -> null;
        };
    }

    /**
     * This encoding with a {@code /Differences} array applied: {@code [code name name ... code
     * name ...]}, each number giving the code of the name after it, the next names the codes
     * after that.
     */
    public SimpleEncoding withDifferences(PdfArray differences) {
        String[] changed = Arrays.copyOf(names, 256);
        int code = -1;
        for (PdfObject item : differences.items()) {
            if (item instanceof PdfNumber n) {
                code = n.intValue();
            } else if (item instanceof PdfName glyph) {
                if (code >= 0 && code < 256) {
                    changed[code] = glyph.name(); // Names past 255 have no code: ignored.
                }
                code++;
            } else {
                throw new UnsupportedContentException("a font's /Differences array has " + item
                        + " where a code or glyph name belongs");
            }
        }
        return new SimpleEncoding(name + " with /Differences", changed);
    }

    /** The glyph name for {@code code} (0-255), or null if the encoding has none. */
    public String glyphName(int code) {
        return names[code];
    }

    /** The Unicode text for {@code code}: empty if the encoding has no glyph for it. */
    public String text(int code) {
        String glyph = names[code];
        return glyph == null ? "" : unicode(glyph);
    }

    /** The Unicode text of a glyph name, {@link #UNKNOWN} if the name isn't known. */
    public static String unicode(String glyphName) {
        String known = UNICODE.get(glyphName);
        if (known != null) {
            return known;
        }
        if (glyphName.startsWith("uni") && glyphName.length() > 3 && (glyphName.length() - 3) % 4 == 0) {
            // uni + one or more four-digit UTF-16 code units: uni00660069 is "fi".
            StringBuilder text = new StringBuilder();
            for (int i = 3; i < glyphName.length(); i += 4) {
                int unit = hex(glyphName.substring(i, i + 4));
                if (unit < 0 || (unit >= 0xD800 && unit <= 0xDFFF)) {
                    return UNKNOWN;
                }
                text.append((char) unit);
            }
            return text.toString();
        }
        if (glyphName.startsWith("u") && glyphName.length() >= 5 && glyphName.length() <= 7) {
            // u + four to six digits: one code point.
            int codePoint = hex(glyphName.substring(1));
            if (codePoint >= 0 && Character.isValidCodePoint(codePoint) && !(codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                return new String(Character.toChars(codePoint));
            }
        }
        return UNKNOWN;
    }

    /** Upper-case hex digits as a number, or -1 if they aren't (the Adobe Glyph List's convention). */
    private static int hex(String digits) {
        for (char c : digits.toCharArray()) {
            if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'F')) {
                return -1;
            }
        }
        return Integer.parseInt(digits, 16);
    }

    @Override
    public String toString() {
        return name;
    }
}
