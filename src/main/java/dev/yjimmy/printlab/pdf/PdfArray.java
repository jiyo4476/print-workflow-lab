package dev.yjimmy.printlab.pdf;

import java.util.List;
import java.util.stream.Collectors;

/** {@code [1 /Name (text)]}: an ordered list of objects of any types. */
public record PdfArray(List<PdfObject> items) implements PdfObject {

    public PdfArray {
        items = List.copyOf(items);
    }

    public static PdfArray of(PdfObject... items) {
        return new PdfArray(List.of(items));
    }

    public int size() {
        return items.size();
    }

    public PdfObject get(int index) {
        return items.get(index);
    }

    @Override
    public String toString() {
        return items.stream().map(Object::toString).collect(Collectors.joining(" ", "[", "]"));
    }
}
