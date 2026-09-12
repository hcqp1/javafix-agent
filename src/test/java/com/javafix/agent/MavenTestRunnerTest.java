package com.javafix.agent;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.tool.MavenTestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：会真的启动 Maven 进程，因此比单元测试慢，
 * 且临时项目第一次构建时需要能访问 Maven 仓库。
 */
class MavenTestRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectFailedMavenTest() throws IOException {

        Path project = newBuggyCalculatorProject();

        TestResult result = new MavenTestRunner().run(project);

        assertFalse(result.isSuccess(), "失败的测试不能被报告成成功");
        assertEquals(1, result.getExitCode(), "Maven 构建失败时的退出码是 1");

        // 这里刻意断言到「哪个测试失败了」，而不是只断言“Maven 失败了”：
        // 依赖下载失败、pom 写错同样会给出 BUILD FAILURE，但那不是本测试要证明的东西
        assertTrue(
                result.getStdout().contains("Tests run: 1, Failures: 1"),
                "期望看到 surefire 的失败汇总，实际输出：\n" + result.getStdout()
        );

        assertTrue(
                result.getStdout().contains("expected: <5> but was: <-1>"),
                "期望看到 Calculator 的断言失败信息，实际输出：\n" + result.getStdout()
        );
    }

    @Test
    void shouldGiveUpWhenMavenTakesTooLong() throws IOException {

        Path project = newBuggyCalculatorProject();

        // 400ms 远小于任何一次真实的 mvn test，必然走到超时分支
        MavenTestRunner runner = new MavenTestRunner(Duration.ofMillis(400));

        TestResult result = runner.run(project);

        assertFalse(result.isSuccess());
        assertEquals(TestResult.EXIT_TIMEOUT, result.getExitCode());
        assertTrue(
                result.getStderr().contains("timed out"),
                "超时要给出可识别的原因，实际：" + result.getStderr()
        );
        assertNotNull(result.getStdout(), "即使超时，也要返回已经产生的输出");
    }

    @Test
    void shouldFailFastWhenProjectDirectoryIsMissing() {

        TestResult result = new MavenTestRunner().run(tempDir.resolve("does-not-exist"));

        assertEquals(TestResult.EXIT_START_FAILURE, result.getExitCode());
        assertTrue(
                result.getStderr().contains("not a directory"),
                "路径不存在要直接说清楚，实际：" + result.getStderr()
        );
    }

    /**
     * 生成一个真实但故意写错的 Maven 项目，返回它的根目录。
     *
     * <p>这个「带 Bug 的 Calculator」同时也是后续 Agent 修 bug 演示与评测的靶子。
     */
    private Path newBuggyCalculatorProject() throws IOException {

        Path mainJava = tempDir.resolve(
                "src/main/java/com/example"
        );

        Path testJava = tempDir.resolve(
                "src/test/java/com/example"
        );

        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        String pom = String.join("\n",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"",
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0",
                "         https://maven.apache.org/xsd/maven-4.0.0.xsd\">",
                "",
                "    <modelVersion>4.0.0</modelVersion>",
                "",
                "    <groupId>com.example</groupId>",
                "    <artifactId>buggy-calculator</artifactId>",
                "    <version>1.0-SNAPSHOT</version>",
                "",
                "    <properties>",
                "        <maven.compiler.source>17</maven.compiler.source>",
                "        <maven.compiler.target>17</maven.compiler.target>",
                "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                "    </properties>",
                "",
                "    <dependencies>",
                "        <dependency>",
                "            <groupId>org.junit.jupiter</groupId>",
                "            <artifactId>junit-jupiter</artifactId>",
                "            <version>5.10.2</version>",
                "            <scope>test</scope>",
                "        </dependency>",
                "    </dependencies>",
                "",
                "    <build>",
                "        <plugins>",
                "            <plugin>",
                "                <groupId>org.apache.maven.plugins</groupId>",
                "                <artifactId>maven-surefire-plugin</artifactId>",
                "                <version>3.2.5</version>",
                "            </plugin>",
                "        </plugins>",
                "    </build>",
                "",
                "</project>"
        );

        Files.write(
                tempDir.resolve("pom.xml"),
                pom.getBytes(StandardCharsets.UTF_8)
        );

        // 故意写错的 Calculator：add 应该是 a + b
        String calculator = String.join("\n",
                "package com.example;",
                "",
                "public class Calculator {",
                "",
                "    public int add(int a, int b) {",
                "        return a - b;",
                "    }",
                "}"
        );

        Files.write(
                mainJava.resolve("Calculator.java"),
                calculator.getBytes(StandardCharsets.UTF_8)
        );

        // 正确的测试：期望 2 + 3 = 5
        String calculatorTest = String.join("\n",
                "package com.example;",
                "",
                "import org.junit.jupiter.api.Test;",
                "",
                "import static org.junit.jupiter.api.Assertions.assertEquals;",
                "",
                "class CalculatorTest {",
                "",
                "    @Test",
                "    void shouldAddTwoNumbers() {",
                "        Calculator calculator = new Calculator();",
                "",
                "        assertEquals(5, calculator.add(2, 3));",
                "    }",
                "}"
        );

        Files.write(
                testJava.resolve("CalculatorTest.java"),
                calculatorTest.getBytes(StandardCharsets.UTF_8)
        );

        return tempDir;
    }
}
