package com.javafix.agent.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Inspects project and Git state using read-only Git commands only. */
public final class WorkspaceInspector {

    private static final long GIT_TIMEOUT_SECONDS = 3;

    public WorkspaceIdentity inspect(Path project) throws IOException {
        Path canonical = project.toRealPath();
        if (!Files.isDirectory(canonical)) {
            throw new IOException("项目目录不存在：" + canonical);
        }

        String root = git(canonical, "rev-parse", "--show-toplevel");
        if (root == null) {
            return new WorkspaceIdentity(canonical.toString(), null, null, null, null, "非 Git 项目");
        }
        String branch = git(canonical, "branch", "--show-current");
        String head = git(canonical, "rev-parse", "HEAD");
        String status = git(canonical, "status", "--porcelain=v1", "--untracked-files=normal");
        if (branch == null || head == null || status == null) {
            throw new IOException("无法读取 Git 工作区状态：" + canonical);
        }
        String summary = status.isBlank() ? "干净" : summarizeStatus(status);
        return new WorkspaceIdentity(canonical.toString(), Path.of(root).toAbsolutePath().normalize().toString(),
                branch.isBlank() ? "(detached HEAD)" : branch, head, digest(status), summary);
    }

    private static String git(Path directory, String... args) throws IOException {
        Path outputFile = Files.createTempFile("javafix-git-status-", ".txt");
        Process process;
        try {
            process = new ProcessBuilder(buildCommand(directory, args))
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
        } catch (IOException e) {
            Files.deleteIfExists(outputFile);
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("cannot run program")) {
                return null;
            }
            throw e;
        }
        try {
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("Git 状态检查超时");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Git 状态检查被中断", e);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static java.util.List<String> buildCommand(Path directory, String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(java.util.List.of(args));
        return command;
    }

    private static String summarizeStatus(String status) {
        String[] lines = status.split("\\R");
        return lines.length + " 项改动（" + lines[0].strip() + (lines.length > 1 ? " …" : "") + ")";
    }

    private static String digest(String status) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(status.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 Java 环境不支持 SHA-256", e);
        }
    }
}
