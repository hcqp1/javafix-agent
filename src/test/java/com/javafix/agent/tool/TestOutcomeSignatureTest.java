package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestOutcomeSignatureTest {
    @TempDir Path temp;

    @Test
    void readsFailureIdentityAndOutcome() throws IOException {
        TestOutcomeSignature signature = TestOutcomeSignature.read(Path.of("src/test/resources/surefire/one-failure"));
        assertEquals(new TestOutcomeSignature.Entry("com.example.CalculatorTest", "shouldAddTwoNumbers", TestOutcomeSignature.Outcome.FAILED), signature.entries().get(0));
    }

    @Test
    void comparesIdentityEvenWhenAggregateCountsMatch() throws IOException {
        TestOutcomeSignature original = TestOutcomeSignature.read(Path.of("src/test/resources/surefire/one-failure"));
        TestOutcomeSignature renamed = TestOutcomeSignature.read(Path.of("src/test/resources/surefire/renamed-test"));
        assertFalse(original.matches(renamed));
    }

    @Test
    void rejectsMalformedXml() throws IOException {
        Path reports = temp.resolve("reports");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.writeString(reports.resolve("TEST-broken.xml"), "<testsuite><testcase>");
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(reports));
    }

    @Test
    void rejectsAnEmptyReportDirectory() throws IOException {
        Path reports = temp.resolve("empty");
        java.nio.file.Files.createDirectories(reports);
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(reports));
    }

    @Test
    void prioritizesErrorThenFailureThenSkipped() throws IOException {
        Path reports = temp.resolve("priority");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.writeString(reports.resolve("TEST-priority.xml"), """
                <testsuite>
                  <testcase classname="example.Priority" name="error"><failure/><skipped/><error/></testcase>
                  <testcase classname="example.Priority" name="failure"><skipped/><failure/></testcase>
                  <testcase classname="example.Priority" name="skipped"><skipped/></testcase>
                  <testcase classname="example.Priority" name="passed"/>
                </testsuite>
                """);
        var entries = TestOutcomeSignature.read(reports).entries();
        assertEquals(List.of(
                new TestOutcomeSignature.Entry("example.Priority", "error", TestOutcomeSignature.Outcome.ERROR),
                new TestOutcomeSignature.Entry("example.Priority", "failure", TestOutcomeSignature.Outcome.FAILED),
                new TestOutcomeSignature.Entry("example.Priority", "passed", TestOutcomeSignature.Outcome.PASSED),
                new TestOutcomeSignature.Entry("example.Priority", "skipped", TestOutcomeSignature.Outcome.SKIPPED)), entries);
    }
}
