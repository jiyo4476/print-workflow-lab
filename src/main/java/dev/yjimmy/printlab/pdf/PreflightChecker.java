package dev.yjimmy.printlab.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Print-readiness checks on one PDF (PRD-11): version, encryption, embedded fonts (page resources
 * and nested Form XObjects), page size and rotation. Each issue is a plain sentence that names its
 * page, or its font, where that applies. Reads only dictionaries, so nothing is decoded.
 */
public final class PreflightChecker {

    /** Forms nested deeper than this are reported rather than walked, so bad input can't recurse without limit. */
    public static final int MAX_FORM_DEPTH = 16;

    /** Standard 14 font names: a PDF viewer supplies these, so they are not embedded in the file. */
    private static final Set<String> STANDARD_14 = Set.of(
            "Courier", "Courier-Bold", "Courier-BoldOblique", "Courier-Oblique",
            "Helvetica", "Helvetica-Bold", "Helvetica-BoldOblique", "Helvetica-Oblique",
            "Times-Bold", "Times-BoldItalic", "Times-Italic", "Times-Roman",
            "Symbol", "ZapfDingbats");

    /** A named page size in points, portrait. */
    public enum PageSize {
        LETTER("letter", 612, 792),
        A4("a4", 595, 842);

        private final String label;
        private final double width;
        private final double height;

        PageSize(String label, double width, double height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        public String label() {
            return label;
        }

        /** The size with this name; the names are the ones workflow YAML uses. */
        public static PageSize named(String name) {
            for (PageSize size : values()) {
                if (size.label.equals(name)) {
                    return size;
                }
            }
            throw new IllegalArgumentException("Unknown page size '" + name + "' (use letter or a4)");
        }
    }

    /**
     * The configured checks. {@code tolerance} is in points, and applies to each side of a page size.
     */
    public record Settings(PdfVersion maxVersion, boolean allowStandard14, List<PageSize> pageSizes,
            double tolerance, boolean allowRotate) {

        public Settings {
            if (maxVersion == null) {
                throw new IllegalArgumentException("maxVersion is required");
            }
            pageSizes = List.copyOf(pageSizes);
            if (pageSizes.isEmpty()) {
                throw new IllegalArgumentException("pageSizes must name at least one size");
            }
            if (tolerance < 0) {
                throw new IllegalArgumentException("tolerance can't be negative: " + tolerance);
            }
        }
    }

    private final Settings settings;

    public PreflightChecker(Settings settings) {
        this.settings = settings;
    }

    /** Every issue in {@code pdf}, in page order; empty when the file passes. */
    public List<String> issues(PdfDocumentReader pdf) {
        List<String> issues = new ArrayList<>();
        if (pdf.isEncrypted()) {
            // The objects of an encrypted file can't be read without its key, so nothing else
            // can be checked, and the encryption is the one issue to report.
            issues.add("file is encrypted, so it cannot be printed as supplied");
            return issues;
        }
        PdfVersion version = pdf.version();
        if (version.compareTo(settings.maxVersion()) > 0) {
            issues.add("PDF version " + version + " is above the allowed " + settings.maxVersion());
        }
        for (PdfPage page : pdf.pages()) {
            checkPage(pdf, page, issues);
        }
        return issues;
    }

    private void checkPage(PdfDocumentReader pdf, PdfPage page, List<String> issues) {
        String where = "page " + page.number() + ": ";
        checkSize(pdf, page, where, issues);
        checkRotation(pdf, page, where, issues);
        Set<String> reportedFonts = new HashSet<>();
        Set<PdfReference> visitedForms = new HashSet<>();
        checkResources(pdf, page.resources(), where, 0, reportedFonts, visitedForms, issues);
    }

    private void checkSize(PdfDocumentReader pdf, PdfPage page, String where, List<String> issues) {
        if (page.mediaBox().isEmpty()) {
            issues.add(where + "no MediaBox");
            return;
        }
        PdfArray box = page.mediaBox().get();
        if (box.size() != 4) {
            issues.add(where + "MediaBox does not have four numbers");
            return;
        }
        double[] c = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!(pdf.resolve(box.get(i)) instanceof PdfNumber n)) {
                issues.add(where + "MediaBox does not have four numbers");
                return;
            }
            c[i] = n.doubleValue();
        }
        double width = Math.abs(c[2] - c[0]);
        double height = Math.abs(c[3] - c[1]);
        for (PageSize size : settings.pageSizes()) {
            if (Math.abs(width - size.width) <= settings.tolerance()
                    && Math.abs(height - size.height) <= settings.tolerance()) {
                return;
            }
        }
        String allowed = settings.pageSizes().stream().map(PageSize::label)
                .reduce((a, b) -> a + " or " + b).orElse("");
        issues.add(where + String.format("page size %.1f x %.1f pt is not allowed (%s)", width, height, allowed));
    }

    private void checkRotation(PdfDocumentReader pdf, PdfPage page, String where, List<String> issues) {
        PdfObject value = page.dictionary().get("Rotate");
        if (value == null || !(pdf.resolve(value) instanceof PdfNumber rotate)) {
            return;
        }
        int degrees = rotate.intValue();
        if (degrees % 90 != 0) {
            issues.add(where + "/Rotate " + degrees + " is not a multiple of 90");
        } else if (Math.floorMod(degrees, 360) != 0 && !settings.allowRotate()) {
            issues.add(where + "page is rotated " + degrees + " degrees");
        }
    }

    /**
     * Checks the fonts in {@code resources}, then walks the Form XObjects it draws. A form is
     * entered once per page (visited by reference), so a form that draws itself stops there.
     */
    private void checkResources(PdfDocumentReader pdf, PdfDictionary resources, String where, int depth,
            Set<String> reportedFonts, Set<PdfReference> visitedForms, List<String> issues) {
        if (pdf.resolve(resources.get("Font")) instanceof PdfDictionary fonts) {
            for (PdfObject entry : fonts.entries().values()) {
                if (pdf.resolve(entry) instanceof PdfDictionary font) {
                    checkFont(pdf, font, where, reportedFonts, issues);
                }
            }
        }
        if (!(pdf.resolve(resources.get("XObject")) instanceof PdfDictionary xobjects)) {
            return;
        }
        for (PdfObject entry : xobjects.entries().values()) {
            if (!(pdf.resolve(entry) instanceof PdfStream form)
                    || !(form.dictionary().get("Subtype") instanceof PdfName subtype)
                    || !"Form".equals(subtype.name())) {
                continue;
            }
            if (entry instanceof PdfReference reference && !visitedForms.add(reference)) {
                continue;
            }
            if (!(form.dictionary().get("Resources") instanceof PdfObject formResources)
                    || !(pdf.resolve(formResources) instanceof PdfDictionary nested)) {
                continue;
            }
            if (depth + 1 > MAX_FORM_DEPTH) {
                issues.add(where + "forms are nested deeper than " + MAX_FORM_DEPTH + " levels");
                return;
            }
            checkResources(pdf, nested, where, depth + 1, reportedFonts, visitedForms, issues);
        }
    }

    private void checkFont(PdfDocumentReader pdf, PdfDictionary font, String where, Set<String> reportedFonts,
            List<String> issues) {
        // A Type 3 font is drawn by glyph procedures in the file, not by a font program.
        if (font.get("Subtype") instanceof PdfName subtype && "Type3".equals(subtype.name())) {
            return;
        }
        PdfDictionary holder = font;
        if (font.get("Subtype") instanceof PdfName subtype && "Type0".equals(subtype.name())
                && pdf.resolve(font.get("DescendantFonts")) instanceof PdfArray descendants
                && descendants.size() > 0
                && pdf.resolve(descendants.get(0)) instanceof PdfDictionary descendant) {
            holder = descendant;
        }
        String baseFont = font.get("BaseFont") instanceof PdfName name
                ? stripSubsetPrefix(name.name()) : "unnamed";
        boolean embedded = pdf.resolve(holder.get("FontDescriptor")) instanceof PdfDictionary descriptor
                && (descriptor.get("FontFile") != null || descriptor.get("FontFile2") != null
                        || descriptor.get("FontFile3") != null);
        if (embedded || settings.allowStandard14() && STANDARD_14.contains(baseFont)) {
            return;
        }
        if (reportedFonts.add(baseFont)) {
            issues.add(where + "font " + baseFont + " not embedded");
        }
    }

    /** {@code ABCDEF+Arial} is a subset of Arial: the six letters name the subset, not the font. */
    static String stripSubsetPrefix(String name) {
        return name.matches("[A-Z]{6}\\+.+") ? name.substring(7) : name;
    }
}
