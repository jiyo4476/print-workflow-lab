package dev.yjimmy.printlab.pdf;

import java.util.Arrays;

/**
 * A dictionary followed by {@code stream ... endstream}. {@link #data()} is the bytes as stored
 * in the file, still encoded by the dictionary's {@code /Filter}; {@link StreamDecoder} decodes
 * them. {@code dataOffset} is where those bytes start in the file.
 */
public record PdfStream(PdfDictionary dictionary, byte[] data, long dataOffset)
        implements PdfObject {

    public PdfStream {
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfStream s && dataOffset == s.dataOffset
                && dictionary.equals(s.dictionary) && Arrays.equals(data, s.data);
    }

    @Override
    public int hashCode() {
        return 31 * dictionary.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return dictionary + " stream[" + data.length + " bytes]";
    }
}
