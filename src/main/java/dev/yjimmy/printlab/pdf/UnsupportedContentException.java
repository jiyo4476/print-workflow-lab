package dev.yjimmy.printlab.pdf;

/**
 * The page uses something the built-in text extraction doesn't handle, such as a CID (Type 0)
 * font or a rotated page. Thrown instead of returning text that might be wrong.
 */
public class UnsupportedContentException extends RuntimeException {

    public UnsupportedContentException(String message) {
        super(message);
    }
}
