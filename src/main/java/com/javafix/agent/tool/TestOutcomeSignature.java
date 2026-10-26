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
        if (!Files.isDirectory(reportsDirectory)) {
            throw new ParseException("Surefire report directory does not exist: " + reportsDirectory);
        }
        List<Path> reports;
        try (Stream<Path> paths = Files.walk(reportsDirectory)) {
            reports = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new ParseException("Unable to list Surefire reports", e);
        }
        if (reports.isEmpty()) {
            throw new ParseException("No Surefire XML reports found in " + reportsDirectory);
        }

        List<Entry> entries = new ArrayList<>();
        for (Path report : reports) {
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
                var document = builder.parse(report.toFile());
                NodeList testcases = document.getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    Element testcase = (Element) testcases.item(i);
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
