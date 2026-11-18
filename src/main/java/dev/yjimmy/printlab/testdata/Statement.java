package dev.yjimmy.printlab.testdata;

import java.util.List;

/**
 * One customer statement in a fake print run, independent of the output format. The PDF and AFP
 * generators both draw it.
 *
 * @param accountNumber ten digits
 * @param name          the customer's name; empty for a "bad address" statement
 * @param street        house number and street
 * @param city          city name
 * @param state         two-letter state code
 * @param zip           five digits, or four for a "bad address" statement
 * @param pages         the transaction lines on each page; one list per page, at least one page
 */
public record Statement(
        String accountNumber, String name, String street, String city, String state, String zip,
        List<List<String>> pages) {

    public Statement {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a statement has at least one page");
        }
        pages = pages.stream().map(List::copyOf).toList();
    }

    public int pageCount() {
        return pages.size();
    }

    /** The third address line, e.g. "Denver, CO 80202". */
    public String cityLine() {
        return city + ", " + state + " " + zip;
    }
}
