package dev.yjimmy.printlab.pdf;

/** {@code 12 0 R}: a reference to indirect object 12, generation 0. */
public record PdfReference(int objectNumber, int generation) implements PdfObject {

    public PdfReference {
        if (objectNumber < 0 || generation < 0) {
            throw new IllegalArgumentException("object and generation numbers can't be negative: "
                    + objectNumber + " " + generation);
        }
    }

    @Override
    public String toString() {
        return objectNumber + " " + generation + " R";
    }
}
