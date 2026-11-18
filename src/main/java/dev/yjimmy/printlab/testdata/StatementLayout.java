package dev.yjimmy.printlab.testdata;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each piece of text sits on a statement page, shared by the PDF and AFP generators so the
 * two formats put the same text in the same places. Positions are in points from the top-left of
 * a US Letter page (612 x 792); {@code y} is the text's baseline.
 *
 * <ul>
 *   <li>Every page header: account number at (400, 40)</li>
 *   <li>First page only: name at (72, 120), street at (72, 134), "City, ST ZIP" at (72, 148), and
 *       the zip alone at (500, 148)</li>
 * </ul>
 * The {@code statements.yaml} workflow's regions match these positions.
 */
public final class StatementLayout {

    /** The institution name printed on every page. */
    public static final String BANK_NAME = "Front Range Credit Union";

    private StatementLayout() {
    }

    /**
     * One piece of text.
     *
     * @param x    start, in points from the left edge
     * @param y    baseline, in points from the top edge
     * @param size font size in points (the PDF uses it; the AFP uses one font throughout)
     * @param bold whether the PDF draws it bold
     */
    public record TextLine(int x, int y, int size, boolean bold, String text) {
    }

    /** The text on page {@code page} (1-based) of {@code statement}, top to bottom. */
    public static List<TextLine> lines(Statement statement, int page) {
        if (page < 1 || page > statement.pageCount()) {
            throw new IllegalArgumentException("page " + page + " of a "
                    + statement.pageCount() + "-page statement");
        }
        List<TextLine> lines = new ArrayList<>();
        lines.add(new TextLine(72, 40, 14, true, BANK_NAME));
        lines.add(new TextLine(340, 40, 10, false, "Account:"));
        lines.add(new TextLine(400, 40, 10, false, statement.accountNumber()));
        lines.add(new TextLine(400, 54, 9, false,
                "Page " + page + " of " + statement.pageCount()));
        if (page == 1) {
            if (!statement.name().isEmpty()) {
                lines.add(new TextLine(72, 120, 11, false, statement.name()));
            }
            lines.add(new TextLine(72, 134, 11, false, statement.street()));
            lines.add(new TextLine(72, 148, 11, false, statement.cityLine()));
            lines.add(new TextLine(500, 148, 8, false, statement.zip()));
        }
        List<String> transactions = statement.pages().get(page - 1);
        for (int i = 0; i < transactions.size(); i++) {
            lines.add(new TextLine(72, 220 + i * 18, 10, false, transactions.get(i)));
        }
        return lines;
    }
}
