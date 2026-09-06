package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class ScormManifestWriterTest {

	@TempDir
	Path tempDir;

	@Test
	void escapesXmlContent() throws Exception {
		ScormManifestWriter writer = new ScormManifestWriter();
		Path manifestPath = writer.write(tempDir, "au.edu.eq.questionbank.chemistry",
				"Chemistry <Revision> & \"Practice\"", Path.of("index.html"),
				List.of(Path.of("index.html"), Path.of("assets", "question & answer.png")));
		Document document = parse(manifestPath);
		NodeList titleNodes = document.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				"title");
		assertEquals("Chemistry <Revision> & \"Practice\"", titleNodes.item(0).getTextContent());
		NodeList fileNodes = document.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				"file");
		assertEquals("assets/question & answer.png", ((Element) fileNodes.item(0)).getAttribute("href"));
	}

	@Test
	void producesDeterministicManifestForSameInput() throws Exception {
		ScormManifestWriter writer = new ScormManifestWriter();
		List<Path> contentFiles = List.of(Path.of("index.html"), Path.of("assets", "revision.css"),
				Path.of("assets", "questions", "question-1.png"));
		Path firstManifest = writer.write(tempDir.resolve("first"), "au.edu.eq.questionbank.chemistry",
				"Chemistry Revision", Path.of("index.html"), contentFiles);
		Path secondManifest = writer.write(tempDir.resolve("second"), "au.edu.eq.questionbank.chemistry",
				"Chemistry Revision", Path.of("index.html"), contentFiles);
		assertEquals(Files.readString(firstManifest), Files.readString(secondManifest));
	}

	@Test
	void rejectsManifestWhenLaunchFileIsNotDeclared() {
		ScormManifestWriter writer = new ScormManifestWriter();
		assertThrows(IllegalArgumentException.class, () -> writer.write(tempDir, "au.edu.eq.questionbank.chemistry",
				"Chemistry Revision", Path.of("index.html"), List.of(Path.of("assets", "revision.css"))));
	}

	@Test
	void rejectsUnsafeAndDuplicateContentPaths() {
		ScormManifestWriter writer = new ScormManifestWriter();
		assertThrows(IllegalArgumentException.class,
				() -> writer.write(tempDir, "au.edu.eq.questionbank.chemistry", "Chemistry Revision",
						Path.of("index.html"), List.of(Path.of("index.html"), Path.of("..", "outside.html"))));
		assertThrows(IllegalArgumentException.class, () -> writer.write(tempDir, "au.edu.eq.questionbank.chemistry",
				"Chemistry Revision", Path.of("index.html"), List.of(Path.of("index.html"), Path.of("index.html"))));
	}

	@Test
	void writesSingleScoScorm12ManifestWithCompleteSortedFileInventory() throws Exception {
		ScormManifestWriter writer = new ScormManifestWriter();
		List<Path> contentFiles = List.of(Path.of("units", "unit-10", "topic-11.html"),
				Path.of("assets", "questions", "question-1.png"), Path.of("index.html"),
				Path.of("assets", "revision.css"), Path.of("assets", "answers", "question-1-answer-01.png"));
		Path manifestPath = writer.write(tempDir, "au.edu.eq.questionbank.chemistry", "Chemistry Revision",
				Path.of("index.html"), contentFiles);
		assertEquals(tempDir.resolve("imsmanifest.xml"), manifestPath);
		assertTrue(Files.isRegularFile(manifestPath));
		Document document = parse(manifestPath);
		Element manifest = document.getDocumentElement();
		assertEquals(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE, manifest.getNamespaceURI());
		assertEquals("au.edu.eq.questionbank.chemistry", manifest.getAttribute("identifier"));
		assertEquals("1", manifest.getAttribute("version"));
		assertEquals("ADL SCORM", text(document, "schema"));
		assertEquals("1.2", text(document, "schemaversion"));
		Element organizations = firstElement(document, "organizations");
		assertEquals("organization_1", organizations.getAttribute("default"));
		Element organization = firstElement(document, "organization");
		assertEquals("organization_1", organization.getAttribute("identifier"));
		Element item = firstElement(document, "item");
		assertEquals("item_1", item.getAttribute("identifier"));
		assertEquals("resource_1", item.getAttribute("identifierref"));
		Element resource = firstElement(document, "resource");
		assertEquals("resource_1", resource.getAttribute("identifier"));
		assertEquals("webcontent", resource.getAttribute("type"));
		assertEquals("sco", resource.getAttributeNS(ScormManifestWriter.ADL_CONTENT_PACKAGING_NAMESPACE, "scormtype"));
		assertEquals("index.html", resource.getAttribute("href"));
		NodeList fileNodes = document.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				"file");
		List<String> actualHrefs = new ArrayList<>();
		for (int index = 0; index < fileNodes.getLength(); index++) {
			Element file = (Element) fileNodes.item(index);
			actualHrefs.add(file.getAttribute("href"));
		}
		assertEquals(List.of("assets/answers/question-1-answer-01.png", "assets/questions/question-1.png",
				"assets/revision.css", "index.html", "units/unit-10/topic-11.html"), actualHrefs);
	}

	private Element firstElement(Document document, String localName) {
		NodeList nodes = document.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				localName);
		return (Element) nodes.item(0);
	}

	private Document parse(Path manifestPath) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		return factory.newDocumentBuilder().parse(manifestPath.toFile());
	}

	private String text(Document document, String localName) {
		return firstElement(document, localName).getTextContent();
	}
}
