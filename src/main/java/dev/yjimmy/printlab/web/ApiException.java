package dev.yjimmy.printlab.web;

/**
 * A request the API refuses: it carries the HTTP status and the message for the JSON error body.
 * {@link JsonServlet} turns it into that body.
 */
final class ApiException extends RuntimeException {

    private final int status;
    private final Long offset;

    ApiException(int status, String message) {
        this(status, message, null);
    }

    /** @param offset the byte offset in a corrupt file, or {@code null} */
    ApiException(int status, String message, Long offset) {
        super(message);
        this.status = status;
        this.offset = offset;
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    int status() {
        return status;
    }

    /** The byte offset of the problem, or {@code null} when it isn't about a byte position. */
    Long offset() {
        return offset;
    }
}
