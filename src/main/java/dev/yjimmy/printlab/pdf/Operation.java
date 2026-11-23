package dev.yjimmy.printlab.pdf;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One content-stream operation (ISO 32000-1, 7.8.2): an operator and the operands written before
 * it, in postfix order, as in {@code /F1 12 Tf} or {@code [(A) 120 (B)] TJ}.
 *
 * <p>An inline image ({@code BI <key value ...> ID <data> EI}) is one operation, {@code BI},
 * whose single operand is a {@link PdfStream}: the image's parameters as the dictionary and its
 * bytes, still encoded, as the data, because that's what an inline image is, a small stream
 * written straight into the content.
 *
 * @param offset where the first operand (or the operator, if it has none) starts in the
 *     decoded content
 */
public record Operation(String operator, List<PdfObject> operands, int offset) {

    public Operation {
        if (operator == null || operator.isEmpty()) {
            throw new IllegalArgumentException("operator is missing");
        }
        operands = List.copyOf(operands);
    }

    public static Operation of(String operator, PdfObject... operands) {
        return new Operation(operator, List.of(operands), 0);
    }

    /** True for {@code BI ... ID ... EI}: {@code operands().get(0)} is the image as a stream. */
    public boolean isInlineImage() {
        return operator.equals("BI") && operands.size() == 1 && operands.get(0) instanceof PdfStream;
    }

    /** The operation as content-stream syntax; an inline image's bytes are given as a count. */
    @Override
    public String toString() {
        if (isInlineImage()) {
            PdfStream image = (PdfStream) operands.get(0);
            return "BI " + image.dictionary() + " ID " + image.length() + " bytes EI";
        }
        if (operands.isEmpty()) {
            return operator;
        }
        return operands.stream().map(Object::toString).collect(Collectors.joining(" ")) + " " + operator;
    }
}
