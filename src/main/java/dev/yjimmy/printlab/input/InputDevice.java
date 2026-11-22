package dev.yjimmy.printlab.input;

import java.nio.file.Path;

/**
 * One hot folder and the workflow it feeds, as in a ProcessDirector input device. Every device
 * has its own folder: no two devices share one.
 *
 * @param name     unique; becomes the {@code inputDevice} job property of every job it starts
 * @param folder   the hot folder (not searched recursively)
 * @param glob     which files in it are inputs, ignoring case; job tickets never are
 * @param workflow the workflow YAML its jobs run
 */
public record InputDevice(String name, Path folder, String glob, Path workflow) {
}
