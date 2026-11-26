package dev.yjimmy.printlab.steps;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text an {@link AddBarcodeStep} encodes: a template whose {@code ${name}} placeholders are
 * filled from the page's values (job id, sequence, page, page count and the document's
 * properties).
 */
final class BarcodeTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final String template;

    BarcodeTemplate(String template) {
        if (template.isEmpty()) {
            throw new IllegalArgumentException("Step param 'template' must not be empty");
        }
        this.template = template;
    }

    /**
     * Fills the placeholders from {@code values}. A placeholder with no value fails, naming the
     * placeholder and the document, so a typo never prints a barcode with the name in it.
     */
    String render(Map<String, String> values, int sequence) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException("Document " + sequence + ": barcode template uses "
                        + "${" + name + "}, but the document has no '" + name + "' value");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
