package com.javafix.agent.task;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only identity and change summary for the project directory associated with a task. */
public record WorkspaceIdentity(
        String projectPath,
        String repositoryRoot,
        String branch,
        String headCommit,
        String statusDigest,
        String statusSummary) {

    public List<String> differences(WorkspaceIdentity current) {
        List<String> differences = new ArrayList<>();
        if (!same(projectPath, current.projectPath)) differences.add("项目路径已变化");
        if (!same(repositoryRoot, current.repositoryRoot)) differences.add("Git 仓库身份已变化");
        if (!same(branch, current.branch)) differences.add("Git 分支已变化");
        if (!same(headCommit, current.headCommit)) differences.add("HEAD commit 已变化");
        if (!same(statusDigest, current.statusDigest)) differences.add("工作区改动状态已变化");
        return List.copyOf(differences);
    }

    private static boolean same(String left, String right) {
        return java.util.Objects.equals(left, right);
    }

    public static String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
