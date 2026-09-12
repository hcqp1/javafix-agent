package com.javafix.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试用的靶子项目：一个真实但故意写错的 Maven 项目。
 *
 * <p>{@code Calculator.add} 被写成减法，而测试期望它做加法，因此 {@code mvn test} 必然失败。
 * 它同时被两处使用：测试执行器的集成测试、Agent 端到端修 bug 测试；
 * 将来构建自带 Bug 样本集时，它也是第一个样本——保持单一来源，避免改了这边忘了那边。
 */
final class BuggyCalculatorProject {

    private BuggyCalculatorProject() {
    }

    /** 在 {@code root} 下生成项目文件，返回项目根目录。 */
    static Path create(Path root) throws IOException {

        Path mainJava = root.resolve("src/main/java/com/example");
        Path testJava = root.resolve("src/test/java/com/example");

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

        Files.write(root.resolve("pom.xml"), pom.getBytes(StandardCharsets.UTF_8));

        // 故意写错的实现：add 应该是 a + b
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

        Files.write(mainJava.resolve("Calculator.java"), calculator.getBytes(StandardCharsets.UTF_8));

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

        Files.write(testJava.resolve("CalculatorTest.java"), calculatorTest.getBytes(StandardCharsets.UTF_8));

        return root;
    }
}
