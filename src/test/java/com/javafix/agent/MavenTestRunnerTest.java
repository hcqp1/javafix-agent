package com.javafix.agent;

import com.javafix.agent.core.TestResult;
import com.javafix.agent.tool.MavenTestRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MavenTestRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDetectFailedMavenTest() throws IOException {

        // 1. 创建 Maven 项目目录
        Path mainJava = tempDir.resolve(
                "src/main/java/com/example"
        );

        Path testJava = tempDir.resolve(
                "src/test/java/com/example"
        );

        Files.createDirectories(mainJava);
        Files.createDirectories(testJava);

        // 2. 创建 pom.xml
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
                "        <maven.compiler.source>8</maven.compiler.source>",
                "        <maven.compiler.target>8</maven.compiler.target>",
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

        // 3. 创建一个故意写错的 Calculator
        String calculator = String.join("\n",
                "package com.example;",
                "",
                "public class Calculator {",
                "",
                "    public int add(int a, int b) {",
                "        return a - b;", // 故意写错
                "    }",
                "}"
        );

        Files.write(
                mainJava.resolve("Calculator.java"),
                calculator.getBytes(StandardCharsets.UTF_8)
        );

        // 4. 创建 Calculator 的 JUnit 测试
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

        // 5. 使用 JavaFix 的 MavenTestRunner 执行这个项目
        MavenTestRunner runner = new MavenTestRunner();

        TestResult result = runner.run(tempDir);

        // 6. 验证 Maven 确实检测到了测试失败
        assertFalse(result.isSuccess());

        assertNotEquals(
                0,
                result.getExitCode()
        );

        assertTrue(
                result.getStdout().contains("BUILD FAILURE")
                        || result.getStdout().contains("Failures")
        );
    }
}