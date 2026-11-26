package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reorders a job's documents by a document property (e.g. zip code for postal presort). Ties keep
 * their original input order, so the result is deterministic.
 *
 * <p>After sorting, documents are renumbered 1..N in the new order, so {@code sequence} is the
 * document's position in mailing order (what {@code ${sequence}} in an {@code AddBarcode}
 * template reads, and the {@code sequence} column of {@code documents.csv}). The number a
 * document had before sorting is kept as {@link Document#inputSequence()}, which is written to
 * {@code documents.csv} as {@code inputSequence}. Ties are broken by that input position, so a
 * second presort still keeps ties in input order.
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
                .thenComparingInt(Document::inputSequence));
        List<Document> documents = job.documents();
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            documents.set(i, new Document(i + 1, d.file(), d.properties(), d.inputSequence()));
        }
    }
}
