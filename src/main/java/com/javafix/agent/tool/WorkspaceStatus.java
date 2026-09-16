package com.javafix.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 判断工作区里的改动——用 git，而不是看某个工具的返回文本。
 *
 * <p>为什么必须这样：原来判定"修复阶段有没有真的改代码"，看的是 {@code write_file} 的返回内容。
 * 结果真实运行里模型改用 {@code shell} 编辑文件，一次 write_file 都没调用，判定直接失效，
 * 修复阶段空手就离开了。工作区状态是绕不过去的——不管用什么工具改的，git 都看得见。
 */
public final class WorkspaceStatus {

    private WorkspaceStatus() {
    }

    /**
     * {@code src/main} 下是否有改动（含新增文件）。
     *
     * @return 有改动返回 true；<b>判断不了的时候也返回 true</b>——
     *         检查本身失败不该把 Agent 永久卡在某个阶段里
     */
    public static boolean mainSourcesChanged(Path root) {
        return hasChangesUnder(root, "src/main/");
    }

    private static boolean hasChangesUnder(Path root, String prefix) {

        Path log;
        try {
            log = Files.createTempFile("javafix-git-status-", ".log");
        } catch (IOException e) {
            return true;
        }

        try {
            List<String> command = ProcessRunner.isWindows()
                    ? List.of("cmd.exe", "/c", "git", "status", "--porcelain", "--untracked-files=all")
                    : List.of("git", "status", "--porcelain", "--untracked-files=all");

            ProcessRunner.Result result =
                    ProcessRunner.run(command, root, log, Duration.ofSeconds(60));

            return result.output().lines()
                    .filter(line -> line.length() > 3)
                    // 注意不能先 strip 整行：porcelain 格式里未暂存的改动是「空格 + M + 空格 + 路径」，
                    // 先 strip 会把状态位吃掉，substring(3) 就切错位置了。
                    .map(line -> line.substring(3).strip().replace('\\', '/'))
                    .anyMatch(path -> path.contains(prefix));

        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } finally {
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // 临时文件删不掉不影响判断结果
            }
        }
    }
}
