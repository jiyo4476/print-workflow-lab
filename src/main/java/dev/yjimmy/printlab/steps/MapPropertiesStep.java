package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames document properties, so input whose index values use other names ({@code ACCT_NO}
 * instead of {@code accountNumber}) works with the same routing, sorting and output steps. It
 * works on any document, whatever step produced it, and changes only properties, never files.
 *
 * <pre>
 *   params:
 *     rename:                       # old name: new name; every name comes from here
 *       ACCT_NO: accountNumber
 *       CUST_NAME: name
 *       ZIP_CODE: zip
 *     requiredProperties: [accountNumber, zip]   # optional, checked after renaming
 * </pre>
 *
 * <ul>
 *   <li>Names match exactly, including case. Properties not in the map are kept, and a name in
 *       the map that a document doesn't have is skipped.
 *   <li>All renames apply together, to the properties as they were before the step, so the map's
 *       order doesn't matter ({@code a: b} and {@code b: a} swap them). A renamed property keeps
 *       its place in the document's property order.
 *   <li>If the document already has a different value under the new name, it's marked
 *       {@code indexError=rename conflict: <old> -> <new>} and both properties stay as they were;
 *       the job continues. The same value under both names is no conflict.
 *   <li>A map that sends two old names to one new name fails when the workflow loads.
 *   <li>A required property that is absent or blank after renaming marks the document
 *       {@code indexError=missing: <names>}.
 * </ul>
 */
public final class MapPropertiesStep implements Step {

    private final Map<String, String> rename = new LinkedHashMap<>();
    private final List<String> requiredProperties;

    public MapPropertiesStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        Map<String, Object> map = params.map("rename");
        if (map.isEmpty()) {
            throw new IllegalArgumentException("Step param 'rename' must map at least one old"
                    + " name to a new name");
        }
        Map<String, String> oldNameFor = new HashMap<>();
        map.forEach((oldName, value) -> {
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("'rename' gives no new name for '" + oldName
                        + "'");
            }
            String newName = value.toString();
            String other = oldNameFor.putIfAbsent(newName, oldName);
            if (other != null) {
                throw new IllegalArgumentException("'rename' maps both '" + other + "' and '"
                        + oldName + "' to '" + newName + "'; a document with both would have"
                        + " two values for it");
            }
            rename.put(oldName, newName);
        });
        this.requiredProperties = params.optionalStringList("requiredProperties");
    }

    @Override
    public void execute(Job job) {
        for (Document doc : job.documents()) {
            apply(doc.properties());
        }
    }

    private void apply(Map<String, String> properties) {
        Set<String> moving = new HashSet<>(rename.keySet());
        moving.retainAll(properties.keySet());

        // A rename conflicts when its new name holds another value that isn't moving away. A
        // conflicting rename stays put, which can make a rename onto its old name conflict too,
        // so repeat until nothing changes.
        Set<String> conflicts = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String oldName : moving) {
                String newName = rename.get(oldName);
                String occupant = properties.get(newName);
                boolean vacated = moving.contains(newName) && !conflicts.contains(newName);
                if (!conflicts.contains(oldName) && occupant != null && !vacated
                        && !occupant.equals(properties.get(oldName))) {
                    conflicts.add(oldName);
                    changed = true;
                }
            }
        }
        moving.removeAll(conflicts);

        Map<String, String> result = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            String target = moving.contains(name) ? rename.get(name) : name;
            // put keeps the first position when a moved property lands on a name already seen
            // (same value) or still to come (the occupant's value is then equal).
            result.put(target, value);
        });
        properties.clear();
        properties.putAll(result);

        rename.forEach((oldName, newName) -> {
            if (conflicts.contains(oldName)) {
                IndexErrors.mark(properties, "rename conflict: " + oldName + " -> " + newName);
            }
        });
        IndexErrors.markMissing(properties, requiredProperties);
    }
}
