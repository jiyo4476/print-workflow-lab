package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import dev.yjimmy.printlab.workflow.rules.Condition;
import dev.yjimmy.printlab.workflow.rules.RuleParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Assigns every document to a named queue, stored as the document property {@code queue}: the
 * equivalent of workflow connectors with rules.
 *
 * <pre>
 *   params:
 *     routes:
 *       - queue: exceptions
 *         when: { property: name, operator: missing }
 *     default: standard
 * </pre>
 *
 * Routes are checked in order and the first match wins. A document that matches none goes to
 * {@code default}; with no {@code default}, it fails the job.
 */
public final class RouteDocumentsStep implements Step {

    /** The document property this step sets, and that {@link WriteOutputStep} groups by. */
    public static final String QUEUE = "queue";

    /** Queue names become folder names, so they're kept to safe characters. */
    static final Pattern QUEUE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private record Route(String queue, Condition when) {}

    private final List<Route> routes = new ArrayList<>();
    private final String defaultQueue;

    public RouteDocumentsStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        Object rawRoutes = rawParams.get("routes");
        if (!(rawRoutes instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Step param 'routes' must be a non-empty list");
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> route)) {
                throw new IllegalArgumentException("Route " + (i + 1) + " must be a map");
            }
            Object queue = route.get("queue");
            if (queue == null) {
                throw new IllegalArgumentException("Route " + (i + 1) + " needs a 'queue'");
            }
            if (!route.containsKey("when")) {
                throw new IllegalArgumentException("Route " + (i + 1) + " ('" + queue
                        + "') needs a 'when'; use 'default' for documents no rule matches");
            }
            if (route.size() != 2) {
                throw new IllegalArgumentException("Route " + (i + 1) + " ('" + queue
                        + "') takes only 'queue' and 'when', got " + route.keySet());
            }
            routes.add(new Route(checkQueueName(queue.toString()), RuleParser.parse(route.get("when"))));
        }
        String fallback = params.string("default", null);
        this.defaultQueue = fallback == null ? null : checkQueueName(fallback);
    }

    static String checkQueueName(String queue) {
        if (!QUEUE_NAME.matcher(queue).matches()) {
            throw new IllegalArgumentException("Queue name '" + queue
                    + "' may only use letters, digits, '.', '_' and '-', and must start with a"
                    + " letter or digit");
        }
        return queue;
    }

    @Override
    public void execute(Job job) {
        for (Document doc : job.documents()) {
            doc.properties().put(QUEUE, queueFor(doc));
        }
    }

    private String queueFor(Document doc) {
        for (Route route : routes) {
            if (route.when().test(doc.properties())) {
                return route.queue();
            }
        }
        if (defaultQueue == null) {
            throw new IllegalStateException("Document " + doc.sequence()
                    + " matches no route and there is no default queue");
        }
        return defaultQueue;
    }
}
