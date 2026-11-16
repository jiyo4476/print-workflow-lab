package dev.yjimmy.printlab.testdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
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
 *
 * <p>Optionally some statements get a bad address, for the exception queue: alternately a blank
 * name and a 4-digit zip. Which statements are bad depends only on the seed and the counts.
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

    private final long seed;
    private final Random random;

    public StatementPdfGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public void generate(int statements, Path out) throws IOException {
        generate(statements, 0, out);
    }

    /**
     * @param badAddresses how many statements get a blank name or a 4-digit zip (alternately, in
     *     statement order), at most {@code statements}
     */
    public void generate(int statements, int badAddresses, Path out) throws IOException {
        if (badAddresses < 0 || badAddresses > statements) {
            throw new IllegalArgumentException("badAddresses must be 0.." + statements
                    + ", got " + badAddresses);
        }
        List<Integer> bad = new ArrayList<>(badStatements(statements, badAddresses));
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
                // Bad values replace good ones after they're drawn, so the rest of the run is the
                // same with or without bad addresses.
                int badIndex = bad.indexOf(s);
                if (badIndex >= 0 && badIndex % 2 == 0) {
                    name = "";
                } else if (badIndex >= 0) {
                    zip = zip.substring(0, 4);
                }

                for (int p = 1; p <= pages; p++) {
                    PDPage page = new PDPage(PDRectangle.LETTER);
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        text(cs, bold, 14, 72, 40, "Front Range Credit Union");
                        text(cs, font, 10, 340, 40, "Account:");
                        text(cs, font, 10, 400, 40, account);
                        text(cs, font, 9, 400, 54, "Page " + p + " of " + pages);
                        if (p == 1) {
                            if (!name.isEmpty()) {
                                text(cs, font, 11, 72, 120, name);
                            }
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

    /**
     * The 0-based statements that get a bad address, in order. Drawn from their own seeded
     * {@link Random} so they don't disturb the main one.
     */
    private Set<Integer> badStatements(int statements, int count) {
        List<Integer> all = new ArrayList<>(IntStream.range(0, statements).boxed().toList());
        Collections.shuffle(all, new Random(~seed));
        return new TreeSet<>(all.subList(0, count));
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
