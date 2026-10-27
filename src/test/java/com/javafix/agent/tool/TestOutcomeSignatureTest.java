package com.javafix.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TestOutcomeSignatureTest {
    @TempDir Path temp;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tests", "failures", "errors", "skipped"})
    void rejectsSuiteCountersThatDisagreeWithDirectTestcases(String counter) throws IOException {
        String xml = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                + "<testcase classname=\"Example\" name=\"test\"/></testsuite>";
        xml = xml.replace(counter + "=\"" + (counter.equals("tests") ? 1 : 0) + "\"", counter + "=\"2\"");
        Files.writeString(temp.resolve("TEST-count.xml"), xml);
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(temp));
    }

    @Test
    void refusesOversizedReportBeforeParsing() throws IOException {
        Files.writeString(temp.resolve("TEST-large.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                        + "<testcase classname=\"Example\" name=\"test\"/>" + " ".repeat(1024 * 1024) + "</testsuite>");
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(temp));
    }

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
    void rejectsXmlWithNonSurefireRoot() throws IOException {
        Path reports = temp.resolve("wrong-root");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-wrong.xml"), "<foo><testcase classname=\"example.Test\" name=\"works\"/></foo>");
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(reports));
    }

    @Test
    void rejectsAnEmptyReportEvenWhenAnotherReportHasTests() throws IOException {
        Path reports = temp.resolve("mixed-empty");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-valid.xml"), "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"example.Test\" name=\"works\"/></testsuite>");
        Files.writeString(reports.resolve("TEST-empty.xml"), "<testsuite tests=\"0\"/>");
        assertThrows(TestOutcomeSignature.ParseException.class, () -> TestOutcomeSignature.read(reports));
    }

    @Test
    void rejectsTestcaseNotDirectlyInsideSuite() throws IOException {
        Path reports = Path.of("src/test/resources/surefire/orphan-testcase");
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
                <testsuite tests="4" failures="1" errors="1" skipped="1">
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
