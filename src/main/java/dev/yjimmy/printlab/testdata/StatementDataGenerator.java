package dev.yjimmy.printlab.testdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * Draws the random statements for a fake print run. The PDF and AFP generators both use it, so the
 * same seed gives the same statements in either format.
 *
 * <p>Optionally some statements get a bad address, for the exception queue: alternately a blank
 * name and a 4-digit zip. Which statements are bad depends only on the seed and the counts, and
 * bad values replace good ones after they're drawn, so the rest of the run is the same with or
 * without bad addresses.
 */
public final class StatementDataGenerator {

    /** Transaction lines on every page. */
    public static final int LINES_PER_PAGE = 20;

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

    public StatementDataGenerator(long seed) {
        this.seed = seed;
    }

    /**
     * @param badAddresses how many statements get a blank name or a 4-digit zip (alternately, in
     *     statement order), at most {@code count}
     */
    public List<Statement> generate(int count, int badAddresses) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be at least 0, got " + count);
        }
        if (badAddresses < 0 || badAddresses > count) {
            throw new IllegalArgumentException("badAddresses must be 0.." + count
                    + ", got " + badAddresses);
        }
        Random random = new Random(seed);
        List<Integer> bad = new ArrayList<>(badStatements(count, badAddresses));
        List<Statement> statements = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            // The draw order is fixed: changing it changes every run generated from a seed.
            String account = String.format("%010d", 1_000_000_000L + random.nextInt(900_000_000));
            String name = pick(random, FIRST) + " " + pick(random, LAST);
            String street = (100 + random.nextInt(9900)) + " " + pick(random, STREETS);
            String[] city = CITIES.get(random.nextInt(CITIES.size()));
            String zip = city[2] + String.format("%02d", random.nextInt(100));
            int pageCount = 1 + random.nextInt(3);
            List<List<String>> pages = new ArrayList<>(pageCount);
            for (int p = 0; p < pageCount; p++) {
                List<String> lines = new ArrayList<>(LINES_PER_PAGE);
                for (int line = 0; line < LINES_PER_PAGE; line++) {
                    lines.add(String.format("2026-10-%02d   Transaction %03d   %10.2f",
                            1 + random.nextInt(28), line + 1,
                            (random.nextInt(200_000) - 100_000) / 100.0));
                }
                pages.add(lines);
            }
            int badIndex = bad.indexOf(s);
            if (badIndex >= 0 && badIndex % 2 == 0) {
                name = "";
            } else if (badIndex >= 0) {
                zip = zip.substring(0, 4);
            }
            statements.add(new Statement(account, name, street, city[0], city[1], zip, pages));
        }
        return statements;
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

    private static <T> T pick(Random random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
