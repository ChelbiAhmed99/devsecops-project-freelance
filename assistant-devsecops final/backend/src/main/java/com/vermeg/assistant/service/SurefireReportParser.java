/**
 * Role : lire les rapports Surefire pour resumer les executions de tests.
 * Contenu : definition de SurefireReportParser et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lit les rapports JUnit XML (format Surefire/Failsafe, mais aussi celui produit
 * par Cypress, Jest-junit, pytest...) : racine {@code <testsuite>} ou {@code <testsuites>}.
 */
@Service
public class SurefireReportParser {

    private static final int MAX_STACK = 3000;

    public List<SuiteResult> parseReportsDirectory(String reportsDirPath) {
        return parseReportsDirectory(reportsDirPath, 0L);
    }

    /**
     * Ne lit que les rapports modifiés depuis {@code modifiedSinceMillis} : évite de
     * présenter comme "nouvelle exécution" d'anciens rapports restés sur disque quand
     * Maven n'a pas pu démarrer ou que la compilation a échoué.
     */
    public List<SuiteResult> parseReportsDirectory(String reportsDirPath, long modifiedSinceMillis) {
        File dir = new File(reportsDirPath);
        List<SuiteResult> results = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) {
            return results;
        }
        File[] xmlFiles = dir.listFiles((d, name) -> name.endsWith(".xml")
                && new File(d, name).lastModified() >= modifiedSinceMillis);
        if (xmlFiles == null) {
            return results;
        }
        for (File xmlFile : xmlFiles) {
            try (InputStream in = new FileInputStream(xmlFile)) {
                results.addAll(parse(in));
            } catch (Exception e) {
                // Un fichier corrompu n'empêche pas de lire les autres rapports.
            }
        }
        results.sort(Comparator.comparing(SuiteResult::getSuiteName));
        return results;
    }

    /** Parse un flux XML JUnit (fichier uploadé par le pipeline CI par exemple). */
    public List<SuiteResult> parse(InputStream xml) throws Exception {
        Document doc = secureBuilder().parse(xml);
        doc.getDocumentElement().normalize();
        Element root = doc.getDocumentElement();
        List<SuiteResult> suites = new ArrayList<>();
        if ("testsuites".equals(root.getTagName())) {
            NodeList children = root.getElementsByTagName("testsuite");
            for (int i = 0; i < children.getLength(); i++) {
                suites.add(parseSuite((Element) children.item(i)));
            }
        } else if ("testsuite".equals(root.getTagName())) {
            suites.add(parseSuite(root));
        }
        return suites;
    }

    private SuiteResult parseSuite(Element testsuite) {
        String suiteName = testsuite.getAttribute("name");
        List<TestCaseResult> testCases = new ArrayList<>();
        NodeList testcaseNodes = testsuite.getElementsByTagName("testcase");
        for (int i = 0; i < testcaseNodes.getLength(); i++) {
            Node node = testcaseNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getParentNode() == testsuite) {
                testCases.add(parseTestCase((Element) node));
            }
        }
        int tests = parseIntSafe(testsuite.getAttribute("tests"));
        int failures = parseIntSafe(testsuite.getAttribute("failures"));
        int errors = parseIntSafe(testsuite.getAttribute("errors"));
        int skipped = parseIntSafe(testsuite.getAttribute("skipped"));
        if (tests == 0 && !testCases.isEmpty()) {
            // Certains outils n'écrivent pas les compteurs : on les recalcule.
            tests = testCases.size();
            failures = (int) testCases.stream().filter(t -> t.getStatus() == TestCaseResult.Status.FAILED).count();
            errors = (int) testCases.stream().filter(t -> t.getStatus() == TestCaseResult.Status.ERROR).count();
            skipped = (int) testCases.stream().filter(t -> t.getStatus() == TestCaseResult.Status.SKIPPED).count();
        }
        return new SuiteResult(suiteName, tests, failures, errors, skipped,
                parseDoubleSafe(testsuite.getAttribute("time")), testCases);
    }

    private TestCaseResult parseTestCase(Element testcaseEl) {
        String name = testcaseEl.getAttribute("name");
        double time = parseDoubleSafe(testcaseEl.getAttribute("time"));

        Element failure = firstChild(testcaseEl, "failure");
        Element error = firstChild(testcaseEl, "error");
        if (failure != null || error != null) {
            Element el = failure != null ? failure : error;
            TestCaseResult result = new TestCaseResult(name,
                    failure != null ? TestCaseResult.Status.FAILED : TestCaseResult.Status.ERROR,
                    el.getAttribute("message"), time);
            String stack = el.getTextContent();
            if (stack != null && !stack.isBlank()) {
                result.setStackTrace(stack.length() > MAX_STACK ? stack.substring(0, MAX_STACK) + "..." : stack.trim());
            }
            if (result.getFailureMessage() == null || result.getFailureMessage().isBlank()) {
                result.setFailureMessage(el.getAttribute("type"));
            }
            return result;
        }
        if (firstChild(testcaseEl, "skipped") != null) {
            return new TestCaseResult(name, TestCaseResult.Status.SKIPPED, null, time);
        }
        return new TestCaseResult(name, TestCaseResult.Status.PASSED, null, time);
    }

    private Element firstChild(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Protection XXE : les rapports peuvent venir d'un pipeline externe.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null); // pas de "[Fatal Error]" sur stderr : l'exception suffit
        return builder;
    }

    private int parseIntSafe(String value) {
        try {
            return Objects.requireNonNull(value).isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDoubleSafe(String value) {
        try {
            return Objects.requireNonNull(value).isBlank() ? 0.0 : Double.parseDouble(value.trim().replace(",", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
