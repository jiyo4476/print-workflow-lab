package dev.jimmyyoung.printlab.workflow;

import java.nio.file.Path;
import java.util.ArrayList;
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
    private JobState state = JobState.RECEIVED;
    private String currentStep;
    private String errorMessage;

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public Job(String id, Path inputFile, Path workDir) {
        this.id = id;
        this.inputFile = inputFile;
        this.workDir = workDir;
        properties.put("inputFileName", inputFile.getFileName().toString());
    }

    public String id() { return id; }
    public Path inputFile() { return inputFile; }
    public Path workDir() { return workDir; }
    public Map<String, String> properties() { return properties; }
    public List<Document> documents() { return documents; }
    public JobState state() { return state; }
    public String currentStep() { return currentStep; }
    public String errorMessage() { return errorMessage; }

    void moveTo(JobState state, String step) {
        this.state = state;
        this.currentStep = step;
    }

    void fail(String step, String message) {
        this.state = JobState.ERROR;
        this.currentStep = step;
        this.errorMessage = message;
    }

    @Override
    public String toString() {
        return "Job[" + id + " " + inputFile.getFileName() + " " + state + "]";
    }
}
