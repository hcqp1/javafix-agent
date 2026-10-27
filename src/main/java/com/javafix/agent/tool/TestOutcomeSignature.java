package com.javafix.agent.tool;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Immutable, identity-sensitive summary of a Surefire report set. */
public final class TestOutcomeSignature {
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::className)
            .thenComparing(Entry::testName)
            .thenComparing(Entry::outcome);

    private final List<Entry> entries;

    private TestOutcomeSignature(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static TestOutcomeSignature read(Path reportsDirectory) throws ParseException {
        return read(reportsDirectory, () -> {});
    }

    static TestOutcomeSignature read(Path reportsDirectory, Runnable checkpoint) throws ParseException {
        checkpoint.run();
        if (!Files.isDirectory(reportsDirectory)) {
            throw new ParseException("Surefire report directory does not exist: " + reportsDirectory);
        }
        List<Path> reports;
        try (Stream<Path> paths = Files.walk(reportsDirectory)) {
            reports = paths.peek(path -> checkpoint.run()).filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new ParseException("Unable to list Surefire reports", e);
        } catch (UncheckedIOException e) {
            throw new ParseException("Unable to traverse Surefire reports: " + e.getMessage(), e);
        }
        if (reports.isEmpty()) {
            throw new ParseException("No Surefire XML reports found in " + reportsDirectory);
        }

        List<Entry> entries = new ArrayList<>();
        for (Path report : reports) {
            checkpoint.run();
            try {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new DefaultHandler() {
                    @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                    @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                });
                byte[] xml;
                try (var input = Files.newInputStream(report)) { xml = input.readNBytes(1024 * 1024 + 1); }
                if (xml.length > 1024 * 1024) throw new ParseException("Surefire report size limit exceeded");
                checkpoint.run();
                var document = builder.parse(new java.io.ByteArrayInputStream(xml));
                checkpoint.run();
                Element root = document.getDocumentElement();
                if (root == null || !(root.getTagName().equals("testsuite") || root.getTagName().equals("testsuites"))) {
                    throw new ParseException("Invalid Surefire report root in " + report);
                }
                NodeList suites = root.getElementsByTagName("testsuite");
                if (root.getTagName().equals("testsuites") && suites.getLength() == 0) {
                    throw new ParseException("No testsuite elements in " + report);
                }
                if (root.getTagName().equals("testsuite")) validateCounts(root, report);
                for (int i = 0; i < suites.getLength(); i++) {
                    checkpoint.run();
                    validateCounts((Element) suites.item(i), report);
                }
                NodeList testcases = root.getElementsByTagName("testcase");
                if (testcases.getLength() == 0) {
                    throw new ParseException("No test cases in Surefire report " + report);
                }
                for (int i = 0; i < testcases.getLength(); i++) {
                    checkpoint.run();
                    Element testcase = (Element) testcases.item(i);
                    if (!(testcase.getParentNode() instanceof Element parent) || !parent.getTagName().equals("testsuite")) {
                        throw new ParseException("Test case is not directly inside a testsuite in " + report);
                    }
                    String className = testcase.getAttribute("classname");
                    String testName = testcase.getAttribute("name");
                    if (className.isEmpty() || testName.isEmpty()) {
                        throw new ParseException("Test case is missing classname or name in " + report);
                    }
                    entries.add(new Entry(className, testName, outcome(testcase)));
                }
            } catch (ParseException e) {
                throw e;
            } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException e) {
                throw new ParseException("Unable to parse Surefire report: " + report, e);
            }
        }
        if (entries.isEmpty()) {
            throw new ParseException("Surefire reports contain no test cases in " + reportsDirectory);
        }
        entries.sort(ENTRY_ORDER);
        return new TestOutcomeSignature(entries);
    }

    private static void validateCounts(Element suite, Path report) throws ParseException {
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        for (Node child = suite.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element testcase && testcase.getTagName().equals("testcase")) {
                tests++;
                switch (outcome(testcase)) {
                    case FAILED -> failures++;
                    case ERROR -> errors++;
                    case SKIPPED -> skipped++;
                    default -> { }
                }
            }
        }
        String[] names = {"tests", "failures", "errors", "skipped"};
        int[] counts = {tests, failures, errors, skipped};
        for (int i = 0; i < names.length; i++) {
            String declared = suite.getAttribute(names[i]);
            if (!declared.matches("[0-9]+") || !declared.equals(Integer.toString(counts[i]))) {
                throw new ParseException("Surefire suite " + names[i] + " differs from direct testcase count in " + report);
            }
        }
    }

    private static Outcome outcome(Element testcase) {
        boolean failure = false;
        boolean skipped = false;
        NodeList children = testcase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                switch (element.getTagName()) {
                    case "error" -> { return Outcome.ERROR; }
                    case "failure" -> failure = true;
                    case "skipped" -> skipped = true;
                    default -> { }
                }
            }
        }
        if (failure) return Outcome.FAILED;
        if (skipped) return Outcome.SKIPPED;
        return Outcome.PASSED;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean matches(TestOutcomeSignature other) {
        return other != null && entries.equals(other.entries);
    }

    public record Entry(String className, String testName, Outcome outcome) { }

    public enum Outcome { PASSED, FAILED, ERROR, SKIPPED }

    public static final class ParseException extends IOException {
        public ParseException(String message) { super(message); }
        public ParseException(String message, Throwable cause) { super(message, cause); }
    }
}
