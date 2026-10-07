package com.vermeg.assistant.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lance un processus externe (mvn, trivy...) avec timeout. Les flux stdout/stderr
 * sont lus dans des threads séparés : lire stdout jusqu'à la fin AVANT d'attendre
 * le processus (comme dans la version initiale) rendait le timeout inopérant.
 */
public final class ProcessRunner {

    private ProcessRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }

        public String combined() {
            return stderr == null || stderr.isBlank() ? stdout : stdout + System.lineSeparator() + stderr;
        }
    }

    public static Result run(List<String> command, File workingDir, int timeoutSeconds, boolean mergeStderr)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(mergeStderr);
        if (workingDir != null) {
            builder.directory(workingDir);
        }
        Process process = builder.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<String> err = mergeStderr
                ? CompletableFuture.completedFuture("")
                : CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new Result(-1, out.getNow(""), "Timeout après " + timeoutSeconds + "s", true);
        }
        return new Result(process.exitValue(), out.join(), err.join(), false);
    }

    public static String mavenCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "mvn.cmd" : "mvn";
    }

    public static String tail(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return "...(tronqué)...\n" + text.substring(text.length() - maxChars);
    }

    private static String readAll(InputStream in) {
        try (in; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
