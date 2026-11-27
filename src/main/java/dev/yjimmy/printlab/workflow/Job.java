package dev.yjimmy.printlab.workflow;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One unit of work moving through a workflow: usually a single input print file.
 *
 * <p>Job properties describe the job as a whole (input file, page count, current phase). The
 * documents inside it carry their own document properties.
 */
public final class Job {

    private final String id;
    private final Path inputFile;
    private final Path workDir;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<Document> documents = new ArrayList<>();
    private final List<StepRecord> stepRecords = new ArrayList<>();
    private JobState state = JobState.RECEIVED;
    private Phase phase = Phase.RECEIVE;
    private String currentStep;
    private String errorMessage;

    /**
     * A new job ID: the first 12 hex digits of a random UUID, which are all random (the version
     * digit comes after them), so 48 bits. Short enough to read and type; across a million jobs
     * the chance of any two sharing an ID is about 0.2%. With 8 digits (32 bits) that chance was
     * already reached at about 4,000 jobs.
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public Job(String id, Path inputFile, Path workDir) {
        this.id = id;
        this.inputFile = inputFile;
        this.workDir = workDir;
        properties.put("inputFileName", inputFile.getFileName().toString());
    }

    /**
     * A job rebuilt from its saved state, so a retry can resume it (PRD-15). The job's documents
     * and properties are copied; the step history is kept as it was.
     *
     * @param state the job's state as saved; a retry sets it to processing again
     */
    public static Job restore(String id, Path inputFile, Path workDir,
            Map<String, String> properties, List<Document> documents, List<StepRecord> steps,
            JobState state, Phase phase, String currentStep, String errorMessage) {
        Job job = new Job(id, inputFile, workDir);
        job.properties.clear();
        job.properties.putAll(properties);
        job.documents.addAll(documents);
        job.stepRecords.addAll(steps);
        job.state = state;
        job.phase = phase;
        job.currentStep = currentStep;
        job.errorMessage = errorMessage;
        return job;
    }

    public String id() { return id; }
    public Path inputFile() { return inputFile; }
    public Path workDir() { return workDir; }
    public Map<String, String> properties() { return properties; }
    public List<Document> documents() { return documents; }
    public JobState state() { return state; }
    /** The workflow phase the job is in: receive until its first step, complete once finished. */
    public Phase phase() { return phase; }
    public String currentStep() { return currentStep; }
    public String errorMessage() { return errorMessage; }

    /** The steps this job has been through so far, in the order they ran. */
    public List<StepRecord> stepRecords() { return Collections.unmodifiableList(stepRecords); }

    void addStepRecord(StepRecord record) {
        stepRecords.add(record);
    }

    void moveTo(JobState state, Phase phase, String step) {
        this.state = state;
        this.phase = phase;
        this.currentStep = step;
    }

    void fail(String step, String message) {
        this.state = JobState.ERROR;
        this.currentStep = step;
        this.errorMessage = message;
    }

    @Override
    public String toString() {
        return "Job[" + id + " " + inputFile.getFileName() + " " + state + " " + phase.yamlName()
                + "]";
    }
}
