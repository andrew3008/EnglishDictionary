package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

final class PomContract {

    static final String GROUP_ID = "space.br1440.platform.starters.tracing";
    static final String ARTIFACT_ID = "platform-tracing-javaagent";
    static final String NAME = "Platform Tracing Controlled OpenTelemetry Java Agent";
    static final String DESCRIPTION =
            "Controlled OpenTelemetry Java agent distribution with the platform tracing extension embedded";

    private PomContract() {
    }

    static void assertMatches(Path pomFile, String expectedVersion) throws IOException {
        Element root = parseXml(pomFile).getDocumentElement();

        assertThat(requiredElementText(root, "groupId")).as(pomFile.toString()).isEqualTo(GROUP_ID);
        assertThat(requiredElementText(root, "artifactId")).as(pomFile.toString()).isEqualTo(ARTIFACT_ID);
        assertThat(requiredElementText(root, "version")).as(pomFile.toString()).isEqualTo(expectedVersion);
        assertThat(optionalElementText(root, "packaging")).as(pomFile.toString()).isIn(null, "jar");
        assertThat(requiredElementText(root, "name")).as(pomFile.toString()).isEqualTo(NAME);
        assertThat(requiredElementText(root, "description")).as(pomFile.toString()).isEqualTo(DESCRIPTION);

        assertThat(root.getElementsByTagName("dependencies").getLength())
                .as("%s: dependencies anywhere in the document", pomFile)
                .isZero();
        assertThat(root.getElementsByTagName("dependencyManagement").getLength())
                .as("%s: dependencyManagement anywhere in the document", pomFile)
                .isZero();
    }

    private static Document parseXml(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage -- идентификатор feature Xerces для запрета DOCTYPE, не сетевой запрос
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IllegalStateException("Failed to parse POM: %s".formatted(file), exception);
        }
    }

    private static String requiredElementText(Element parent, String tagName) {
        String value = optionalElementText(parent, tagName);
        if (value == null) {
            throw new IllegalStateException("Required POM element is missing: project/%s".formatted(tagName));
        }
        return value;
    }

    private static String optionalElementText(Element parent, String tagName) {
        Element found = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                if (found != null) {
                    throw new IllegalStateException("Duplicate POM element: project/%s".formatted(tagName));
                }
                found = element;
            }
        }
        return (found == null) ? null : found.getTextContent().trim();
    }

    static Path writeFixture(Path directory, String fileName, String projectFields) throws IOException {
        Path pomFile = directory.resolve(fileName);
        Files.writeString(pomFile, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                %s</project>
                """.formatted(projectFields), StandardCharsets.UTF_8);
        return pomFile;
    }
}
