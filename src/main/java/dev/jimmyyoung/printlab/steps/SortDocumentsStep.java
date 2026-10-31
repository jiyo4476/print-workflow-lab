package dev.jimmyyoung.printlab.steps;

import dev.jimmyyoung.printlab.workflow.Document;
import dev.jimmyyoung.printlab.workflow.Job;
import dev.jimmyyoung.printlab.workflow.Step;
import java.util.Comparator;
import java.util.Map;

/**
 * Reorders a job's documents by a document property (e.g. zip code for postal presort). Ties keep
 * their original input order, so the result is deterministic.
 */
public final class SortDocumentsStep implements Step {

    private final String property;

    public SortDocumentsStep(Map<String, Object> rawParams) {
        this.property = new Params(rawParams).string("by");
    }

    @Override
    public void execute(Job job) {
        job.documents().sort(Comparator
                .comparing((Document d) -> d.property(property),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Document::sequence));
    }
}
