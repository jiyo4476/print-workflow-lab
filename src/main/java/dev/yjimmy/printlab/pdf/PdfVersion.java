package dev.yjimmy.printlab.pdf;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PDF version such as 1.7: from the {@code %PDF-1.7} header, or from the catalog's
 * {@code /Version} name, which raises it (ISO 32000-1, 7.5.2 and 7.7.2).
 *
 * <p>Every version from 1.0 to 2.0 shares the object syntax this package reads, so no version is
 * rejected; one later than {@link #LATEST_KNOWN} is read as if it were 2.0 and reported as
 * unknown (PRD-07, FR-9).
 */
public record PdfVersion(int major, int minor) implements Comparable<PdfVersion> {

    /** PDF 2.0 (ISO 32000-2), the newest version this reader knows. */
    public static final PdfVersion LATEST_KNOWN = new PdfVersion(2, 0);

    private static final Pattern VERSION = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})");

    public PdfVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("version numbers can't be negative: " + major + "." + minor);
        }
    }

    /** {@code "1.7"} gives 1.7; anything that isn't {@code digits.digits} gives empty. */
    public static Optional<PdfVersion> parse(String text) {
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PdfVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }

    /** True from 1.0 up to {@link #LATEST_KNOWN}. */
    public boolean isKnown() {
        return major >= 1 && compareTo(LATEST_KNOWN) <= 0;
    }

    @Override
    public int compareTo(PdfVersion other) {
        return major != other.major ? Integer.compare(major, other.major)
                : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
