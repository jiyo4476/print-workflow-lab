package dev.yjimmy.printlab.testdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Generates a fake "statement print run": many customer statements concatenated into one PDF,
 * like the output of a billing system. Every value sits at a fixed position so the workflow can
 * find it.
 *
 * <p>Layout (points, measured from the top-left, US Letter 612 x 792):
 * <ul>
 *   <li>Every page header: account number at (400, 40)</li>
 *   <li>First page only: name at (72, 120), street at (72, 134), "City, ST ZIP" at (72, 148), and
 *       the zip alone at (500, 148)</li>
 * </ul>
 * The {@code statements.yaml} workflow's regions match these positions.
 */
public final class StatementPdfGenerator {

    static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();

    private static final List<String> FIRST = List.of("Avery", "Jordan", "Riley", "Casey", "Morgan",
            "Taylor", "Quinn", "Drew", "Reese", "Skyler", "Parker", "Rowan");
    private static final List<String> LAST = List.of("Nguyen", "Garcia", "Smith", "Patel", "Kim",
            "Johnson", "Lopez", "Brown", "Martinez", "Lee", "Walker", "Young");
    private static final List<String> STREETS = List.of("Main St", "Oak Ave", "Colfax Ave",
            "Broadway", "Pearl St", "Elm St", "Market St", "Federal Blvd");
    private static final List<String[]> CITIES = List.of(
            new String[] {"Denver", "CO", "802"}, new String[] {"Boulder", "CO", "803"},
            new String[] {"Phoenix", "AZ", "850"}, new String[] {"Austin", "TX", "787"},
            new String[] {"Seattle", "WA", "981"});

    private final Random random;

    public StatementPdfGenerator(long seed) {
        this.random = new Random(seed);
    }

    public void generate(int statements, Path out) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        try (PDDocument doc = new PDDocument()) {
            for (int s = 0; s < statements; s++) {
                String account = String.format("%010d", 1_000_000_000L + random.nextInt(900_000_000));
                String name = pick(FIRST) + " " + pick(LAST);
                String street = (100 + random.nextInt(9900)) + " " + pick(STREETS);
                String[] city = CITIES.get(random.nextInt(CITIES.size()));
                String zip = city[2] + String.format("%02d", random.nextInt(100));
                int pages = 1 + random.nextInt(3);

                for (int p = 1; p <= pages; p++) {
                    PDPage page = new PDPage(PDRectangle.LETTER);
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        text(cs, bold, 14, 72, 40, "Front Range Credit Union");
                        text(cs, font, 10, 340, 40, "Account:");
                        text(cs, font, 10, 400, 40, account);
                        text(cs, font, 9, 400, 54, "Page " + p + " of " + pages);
                        if (p == 1) {
                            text(cs, font, 11, 72, 120, name);
                            text(cs, font, 11, 72, 134, street);
                            text(cs, font, 11, 72, 148, city[0] + ", " + city[1] + " " + zip);
                            text(cs, font, 8, 500, 148, zip);
                        }
                        for (int line = 0; line < 20; line++) {
                            text(cs, font, 10, 72, 220 + line * 18, String.format(
                                    "2026-10-%02d   Transaction %03d   %10.2f",
                                    1 + random.nextInt(28), line + 1,
                                    (random.nextInt(200_000) - 100_000) / 100.0));
                        }
                    }
                }
            }
            doc.save(out.toFile());
        }
    }

    /** Draws text with its baseline at {@code yFromTop} points below the top of the page. */
    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x,
            float yFromTop, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, PAGE_HEIGHT - yFromTop);
        cs.showText(value);
        cs.endText();
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
