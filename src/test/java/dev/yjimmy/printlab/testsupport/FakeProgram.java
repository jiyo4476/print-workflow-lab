package dev.yjimmy.printlab.testsupport;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in external program for {@code RunExternalProgramStep} tests. It behaves the same on
 * every OS because it runs on the current JVM:
 * <pre>
 *   echo ARGS...   prints the working directory, then each argument on its own line; exits 0
 *   exit N         prints "exiting N" and exits with code N
 *   sleep MILLIS   sleeps, then exits 0
 * </pre>
 */
public final class FakeProgram {

    private FakeProgram() {
    }

    public static void main(String[] args) throws InterruptedException {
        switch (args[0]) {
            case "echo" -> {
                System.out.println("cwd=" + Path.of("").toAbsolutePath());
                for (int i = 1; i < args.length; i++) {
                    System.out.println("arg=" + args[i]);
                }
            }
            case "exit" -> {
                System.out.println("exiting " + args[1]);
                System.exit(Integer.parseInt(args[1]));
            }
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }

    /** The command that runs this program with {@code args}, for a step's {@code command}. */
    public static List<String> command(String... args) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-cp");
        command.add(classesDir());
        command.add(FakeProgram.class.getName());
        command.addAll(List.of(args));
        return command;
    }

    private static String classesDir() {
        try {
            return Path.of(FakeProgram.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
