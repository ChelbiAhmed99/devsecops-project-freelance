/**
 * Role : extraire les mesures de couverture depuis le rapport JaCoCo.
 * Contenu : definition de JacocoReportParser et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Optional;

/**
 * Lit la couverture globale d'un rapport JaCoCo XML (target/site/jacoco/jacoco.xml) :
 * compteurs LINE et BRANCH situés directement sous l'élément racine {@code <report>}.
 * Le rapport JaCoCo déclare une DTD : on l'autorise mais sans jamais la charger.
 */
@Service
public class JacocoReportParser {

    public record Coverage(Double linePercent, Double branchPercent) { }

    public Optional<Coverage> parseFile(String path) {
        File file = new File(path);
        if (!file.isFile()) return Optional.empty();
        try (InputStream in = new FileInputStream(file)) {
            return parse(in);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<Coverage> parse(InputStream xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(xml);
            Element root = doc.getDocumentElement();
            Double line = null;
            Double branch = null;
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE || !"counter".equals(n.getNodeName())) continue;
                Element counter = (Element) n;
                double missed = Double.parseDouble(counter.getAttribute("missed"));
                double covered = Double.parseDouble(counter.getAttribute("covered"));
                double pct = missed + covered == 0 ? 0 : Math.round(10000.0 * covered / (missed + covered)) / 100.0;
                if ("LINE".equals(counter.getAttribute("type"))) line = pct;
                if ("BRANCH".equals(counter.getAttribute("type"))) branch = pct;
            }
            return line == null && branch == null ? Optional.empty() : Optional.of(new Coverage(line, branch));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
