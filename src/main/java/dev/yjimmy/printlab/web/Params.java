package dev.yjimmy.printlab.web;

import jakarta.servlet.http.HttpServletRequest;

/** Reads query parameters, refusing what isn't a whole number in range with a 400. */
final class Params {

    private Params() {}

    /** The parameter's value, or {@code null} if it's missing or blank. */
    static String text(HttpServletRequest req, String name) {
        String value = req.getParameter(name);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * A whole number from {@code name}, or {@code defaultValue} when it's missing. A value above
     * {@code max} is capped to {@code max}; one below {@code min}, or not a number, is a 400.
     */
    static int integer(HttpServletRequest req, String name, int defaultValue, int min, int max) {
        String raw = text(req, name);
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a whole number, not '" + raw + "'");
        }
        if (value < min) {
            throw ApiException.badRequest(name + " must be at least " + min + ", not " + value);
        }
        return Math.min(value, max);
    }
}
