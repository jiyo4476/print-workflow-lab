package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfFormatException;
import dev.yjimmy.printlab.pdf.PdfVersion;
import dev.yjimmy.printlab.pdf.PreflightChecker;
import dev.yjimmy.printlab.pdf.PreflightChecker.PageSize;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks each PDF document for print-readiness (PRD-11) and records the result as two document
 * properties: {@code preflight} ({@code pass} or {@code fail}) and {@code preflightIssues}, the
 * issues joined by {@code "; "} (empty on a pass). Routing rules send failures to an exception
 * queue. With {@code failJob: true}, the job fails at the first failing document instead.
 *
 * <pre>
 *   params:
 *     maxVersion: "1.7"          # default 1.7
 *     allowStandard14: false     # Standard 14 fonts pass without an embedded font
 *     pageSizes: [letter]        # letter, a4; default letter
 *     tolerance: 1               # points, per side of the page size; default 1
 *     allowRotate: false         # a non-zero /Rotate fails unless true
 *     failJob: false
 * </pre>
 *
 * Documents without {@code format: pdf} (AFP) are left alone.
 */
public final class PreflightPdfStep implements Step {

    /** The document property holding {@code pass} or {@code fail}. */
    public static final String RESULT = "preflight";

    /** The document property holding the issues, joined by {@code "; "}. */
    public static final String ISSUES = "preflightIssues";

    /** Issues beyond this many are summarised, so one bad file can't write a huge property. */
    static final int MAX_ISSUES_LISTED = 20;

    private static final Logger log = LoggerFactory.getLogger(PreflightPdfStep.class);

    private final PreflightChecker checker;
    private final boolean failJob;

    public PreflightPdfStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        PdfVersion maxVersion = PdfVersion.parse(params.string("maxVersion", "1.7"))
                .orElseThrow(() -> new IllegalArgumentException("Step param 'maxVersion' must look like 1.7"));
        List<String> names = params.optionalStringList("pageSizes");
        List<PageSize> pageSizes = new ArrayList<>();
        for (String name : names.isEmpty() ? List.of("letter") : names) {
            pageSizes.add(PageSize.named(name));
        }
        double tolerance = params.number("tolerance", 1);
        this.checker = new PreflightChecker(new PreflightChecker.Settings(maxVersion,
                params.bool("allowStandard14", false), pageSizes, tolerance,
                params.bool("allowRotate", false)));
        this.failJob = params.bool("failJob", false);
    }

    @Override
    public void execute(Job job) {
        for (Document document : job.documents()) {
            if (!"pdf".equals(document.property("format"))) {
                continue;
            }
            List<String> issues = check(document);
            document.properties().put(RESULT, issues.isEmpty() ? "pass" : "fail");
            document.properties().put(ISSUES, summary(issues));
            if (!issues.isEmpty() && failJob) {
                throw new IllegalStateException("document " + document.sequence()
                        + " failed preflight: " + issues.get(0));
            }
        }
    }

    /** The issues for one document; a file that can't be read is itself an issue, not a crash. */
    private List<String> check(Document document) {
        try {
            return checker.issues(PdfDocumentReader.open(document.file()));
        } catch (IOException | RuntimeException e) {
            log.warn("Preflight could not read document {} ({})", document.sequence(), document.file(), e);
            return List.of(unreadable(e));
        }
    }

    /**
     * The issue for a file the check failed on, in operator's words (PRD-11 NFR-1). The reader's
     * own message (byte offsets, object numbers) and any internal error go to the log only.
     */
    static String unreadable(Exception e) {
        return e instanceof PdfFormatException || e instanceof IOException
                ? "could not check this file: it is not a readable PDF"
                : "could not check this file";
    }

    private static String summary(List<String> issues) {
        if (issues.size() <= MAX_ISSUES_LISTED) {
            return String.join("; ", issues);
        }
        int hidden = issues.size() - MAX_ISSUES_LISTED;
        return String.join("; ", issues.subList(0, MAX_ISSUES_LISTED))
                + "; and " + hidden + " more issue" + (hidden == 1 ? "" : "s");
    }
}
