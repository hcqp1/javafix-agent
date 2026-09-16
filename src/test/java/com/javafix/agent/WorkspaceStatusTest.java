package com.javafix.agent;

import com.javafix.agent.tool.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 git 判断"src/main 有没有被改过"。
 *
 * <p>这条检测存在的理由：原来靠 write_file 的返回文本判断，模型改用 shell 编辑文件就绕过去了。
 */
class WorkspaceStatusTest {

    @TempDir
    Path project;

    @Test
    void shouldDetectChangesUnderMainSources() throws Exception {

        git("init", "-q");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/A.java"), "class A {}");
        git("add", ".");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        assertFalse(WorkspaceStatus.mainSourcesChanged(project), "刚提交完不该算有改动");

        Files.writeString(project.resolve("src/main/java/A.java"), "class A { int x; }");

        assertTrue(WorkspaceStatus.mainSourcesChanged(project), "src/main 下的改动要能被发现");
    }

    @Test
    void shouldIgnoreChangesOutsideMainSources() throws Exception {

        git("init", "-q");
        Files.writeString(project.resolve("README.md"), "hello");

        assertFalse(WorkspaceStatus.mainSourcesChanged(project), "src/main 之外的改动不算数");
    }

    private void git(String... arguments) throws Exception {

        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();

        process.getInputStream().readAllBytes();
        process.waitFor();
    }
}
