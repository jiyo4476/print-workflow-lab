package dev.yjimmy.printlab.afp;

/**
 * A Tag Logical Element: one name/value index tag, such as {@code accountNumber = 1234567890}.
 * TLEs inside a named page group (BNG ... ENG) describe that group's document, which is how AFP
 * carries document properties without anyone reading text off the page.
 */
public record Tle(String name, String value) {
}
