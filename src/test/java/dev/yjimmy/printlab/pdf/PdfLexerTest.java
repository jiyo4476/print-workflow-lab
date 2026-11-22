package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.pdf.PdfLexer.Kind;
import dev.yjimmy.printlab.pdf.PdfLexer.Token;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class PdfLexerTest {

    static List<Token> lex(String pdf) {
        return lex(pdf.getBytes(StandardCharsets.ISO_8859_1));
    }

    static List<Token> lex(byte[] pdf) {
        PdfLexer lexer = new PdfLexer(pdf);
        List<Token> tokens = new ArrayList<>();
        for (Token t = lexer.next(); t.kind() != Kind.END_OF_FILE; t = lexer.next()) {
            tokens.add(t);
        }
        return tokens;
    }

    static PdfObject single(String pdf) {
        List<Token> tokens = lex(pdf);
        assertEquals("tokens in " + pdf, 1, tokens.size());
        assertEquals("kind", Kind.VALUE, tokens.get(0).kind());
        return tokens.get(0).value();
    }

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @RunWith(Parameterized.class)
    public static class Numbers {

        @Parameters(name = "{0}")
        public static List<Object[]> numbers() {
            return Arrays.asList(new Object[][] {
                {"42", "42", false},
                {"-3", "-3", false},
                {"+7", "7", false},
                {"0", "0", false},
                {"3.14", "3.14", true},
                {"-.5", "-0.5", true},
                {"4.", "4", true},
                {"+.002", "0.002", true},
                {"-0.0", "0.0", true},
            });
        }

        private final String text;
        private final String value;
        private final boolean real;

        public Numbers(String text, String value, boolean real) {
            this.text = text;
            this.value = value;
            this.real = real;
        }

        @Test
        public void lexesAsANumber() {
            PdfNumber number = (PdfNumber) single(text);

            assertEquals("value", 0, new BigDecimal(value).compareTo(number.value()));
            assertEquals("real", real, number.real());
        }
    }

    @RunWith(Parameterized.class)
    public static class MalformedNumbers {

        @Parameters(name = "{0}")
        public static List<Object[]> numbers() {
            return Arrays.asList(new Object[][] {{"1.2.3"}, {"--5"}, {"+-1"}, {"."}, {"+"}, {"12a"}});
        }

        private final String text;

        public MalformedNumbers(String text) {
            this.text = text;
        }

        @Test
        public void failsWithItsOffset() {
            PdfFormatException e = assertThrows(PdfFormatException.class, () -> lex("  " + text));

            assertEquals("offset", 2, e.offset());
            assertThat(e).hasMessageContaining("malformed number '" + text + "'");
        }
    }

    public static class Tokens {

        @Test
        public void bigIntegersAreNumbersButNotInts() {
            PdfNumber number = (PdfNumber) single("123456789012");

            assertEquals("value", new BigDecimal("123456789012"), number.value());
            assertEquals("fits in an int", false, number.isInt());
            assertThrows(ArithmeticException.class, number::intValue);
        }

        @Test
        public void nameWithHexEscapeDecodes() {
            assertEquals("name", PdfName.of("A B"), single("/A#20B"));
            assertEquals("lower-case hex", PdfName.of("a/b"), single("/a#2fb"));
        }

        @Test
        public void namesEndAtDelimitersAndMayLookLikeNumbers() {
            assertThat(lex("/Type/Page/1.5[/]")).extracting(Token::toString)
                    .containsExactly("/Type", "/Page", "/1.5", "[", "/", "]");
        }

        @Test
        public void slashAloneIsTheEmptyName() {
            assertEquals("name", PdfName.of(""), single("/ "));
        }

        @Test
        public void hashWithoutTwoHexDigitsInANameFails() {
            PdfFormatException e = assertThrows(PdfFormatException.class, () -> lex("/ab#2"));

            assertEquals("offset of the #", 3, e.offset());
        }

        @Test
        public void literalStringWithNestedParentheses() {
            assertEquals("string", PdfString.of("a (b (c)) d"), single("(a (b (c)) d)"));
        }

        @Test
        public void literalStringEscapes() {
            assertEquals("\\n \\) \\\\ \\053", PdfString.of("\n)\\+"), single("(\\n\\)\\\\\\053)"));
            assertEquals("\\r \\t \\b \\f \\(", PdfString.of("\r\t\b\f("), single("(\\r\\t\\b\\f\\()"));
        }

        @Test
        public void octalEscapesTakeAtMostThreeDigits() {
            assertEquals("\\0053 is byte 5 then '3'", new PdfString(bytes(5, '3')), single("(\\0053)"));
            assertEquals("\\7 is one digit", new PdfString(bytes(7, 'x')), single("(\\7x)"));
            assertEquals("\\777 overflows to 0xFF", new PdfString(bytes(0xFF)), single("(\\777)"));
        }

        @Test
        public void unknownEscapeIsTheCharacterItself() {
            assertEquals("string", PdfString.of("q"), single("(\\q)"));
        }

        @Test
        public void backslashAtEndOfLineContinuesTheString() {
            assertEquals("LF", PdfString.of("abcdef"), single("(abc\\\ndef)"));
            assertEquals("CR LF", PdfString.of("abcdef"), single("(abc\\\r\ndef)"));
            assertEquals("CR", PdfString.of("abcdef"), single("(abc\\\rdef)"));
        }

        @Test
        public void unescapedEndOfLineInAStringIsOneLineFeed() {
            assertEquals("string", PdfString.of("a\nb\nc\nd"), single("(a\r\nb\rc\nd)"));
        }

        @Test
        public void unterminatedLiteralStringFailsAtItsStart() {
            PdfFormatException e = assertThrows(PdfFormatException.class, () -> lex("1 (a (b)"));

            assertEquals("offset", 2, e.offset());
            assertThat(e).hasMessageContaining("not closed with ')'");
        }

        @Test
        public void backslashAtEndOfFileFails() {
            assertThrows(PdfFormatException.class, () -> lex("(abc\\"));
        }

        @Test
        public void hexStringWithOddDigitCountPadsZero() {
            assertEquals("string", new PdfString(bytes(0x90, 0x1F, 0xA0)), single("<901FA>"));
        }

        @Test
        public void hexStringIgnoresWhiteSpaceAndCase() {
            assertEquals("string", PdfString.of("Hel"), single("<48 65\n6c>"));
            assertEquals("empty", new PdfString(new byte[0]), single("<>"));
        }

        @Test
        public void badHexDigitFailsAtTheDigit() {
            PdfFormatException e = assertThrows(PdfFormatException.class, () -> lex("<48G5>"));

            assertEquals("offset", 3, e.offset());
            assertThat(e).hasMessageContaining("'G' is not a hex digit");
        }

        @Test
        public void unterminatedHexStringFails() {
            PdfFormatException e = assertThrows(PdfFormatException.class, () -> lex("<4865"));

            assertEquals("offset", 0, e.offset());
        }

        @Test
        public void commentsAreSkipped() {
            assertThat(lex("%PDF-1.7\n42 % a comment\r/Name%x\n(not % a comment)"))
                    .extracting(Token::toString)
                    .containsExactly("42", "/Name", "(not % a comment)");
        }

        @Test
        public void keywordsAreRecognized() {
            List<Token> tokens = lex("12 0 obj true false null R endobj stream startxref");

            assertThat(tokens).extracting(Token::kind).containsExactly(Kind.VALUE, Kind.VALUE,
                    Kind.KEYWORD, Kind.KEYWORD, Kind.KEYWORD, Kind.KEYWORD, Kind.KEYWORD,
                    Kind.KEYWORD, Kind.KEYWORD, Kind.KEYWORD);
            assertThat(tokens.subList(2, tokens.size())).extracting(Token::keyword).containsExactly(
                    "obj", "true", "false", "null", "R", "endobj", "stream", "startxref");
            assertEquals("isKeyword", true, tokens.get(2).isKeyword("obj"));
        }

        @Test
        public void delimitersNeedNoSpaces() {
            List<Token> tokens = lex("<</A[1 2]/B<</C(x)>>>>");

            assertThat(tokens).extracting(Token::toString).containsExactly(
                    "<<", "/A", "[", "1", "2", "]", "/B", "<<", "/C", "(x)", ">>", ">>");
            assertThat(tokens).extracting(Token::offset)
                    .containsExactly(0, 2, 4, 5, 7, 8, 9, 11, 13, 15, 18, 20);
        }

        @Test
        public void bracesAreKeywordsForTheParserToReject() {
            assertThat(lex("{ }")).extracting(Token::keyword).containsExactly("{", "}");
        }

        @Test
        public void loneClosingBracketsFail() {
            assertThat(assertThrows(PdfFormatException.class, () -> lex("1 > 2")).offset())
                    .isEqualTo(2);
            assertThat(assertThrows(PdfFormatException.class, () -> lex("1 ) 2")).offset())
                    .isEqualTo(2);
        }

        @Test
        public void allSixWhiteSpaceCharactersSeparateTokens() {
            assertThat(lex(bytes('1', 0x00, '2', 0x09, '3', 0x0A, '4', 0x0C, '5', 0x0D, '6', 0x20, '7')))
                    .hasSize(7);
        }

        @Test
        public void endOfFileIsATokenAtTheEnd() {
            PdfLexer lexer = new PdfLexer("42  ".getBytes(StandardCharsets.US_ASCII));
            lexer.next();

            Token end = lexer.next();

            assertEquals("kind", Kind.END_OF_FILE, end.kind());
            assertEquals("offset", 4, end.offset());
            assertEquals("text", "end of file", end.toString());
        }

        @Test
        public void seekMovesTheLexer() {
            PdfLexer lexer = new PdfLexer("1 2 3".getBytes(StandardCharsets.US_ASCII), 2);

            assertEquals("first token", "2", lexer.next().toString());
            lexer.seek(4);
            assertEquals("after seek", "3", lexer.next().toString());
            assertEquals("position", 5, lexer.position());
            assertThrows(PdfFormatException.class, () -> lexer.seek(6));
            assertThrows(PdfFormatException.class, () -> lexer.seek(-1));
        }
    }
}
