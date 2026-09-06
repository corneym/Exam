package au.edu.eq.questionbank.output.scorm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public final class ScormManifestWriter {

	public static final String MANIFEST_FILE_NAME = "imsmanifest.xml";
	static final String ADL_CONTENT_PACKAGING_NAMESPACE = "http://www.adlnet.org/xsd/adlcp_rootv1p2";
	static final String IMS_CONTENT_PACKAGING_NAMESPACE = "http://www.imsproject.org/xsd/imscp_rootv1p1p2";
	static final String IMS_METADATA_NAMESPACE = "http://www.imsglobal.org/xsd/imsmd_rootv1p2p1";
	private static final String ORGANIZATION_IDENTIFIER = "organization_1";
	private static final String ITEM_IDENTIFIER = "item_1";
	private static final String RESOURCE_IDENTIFIER = "resource_1";
	private static final String SCHEMA_LOCATION = IMS_CONTENT_PACKAGING_NAMESPACE + " imscp_rootv1p1p2.xsd "
			+ IMS_METADATA_NAMESPACE + " imsmd_rootv1p2p1.xsd " + ADL_CONTENT_PACKAGING_NAMESPACE
			+ " adlcp_rootv1p2.xsd";

	public Path write(Path packageRoot, String manifestIdentifier, String title, Path launchFile,
			List<Path> contentFiles) throws IOException {
		Objects.requireNonNull(packageRoot, "packageRoot");
		requireNonBlank(manifestIdentifier, "manifestIdentifier");
		requireNonBlank(title, "title");
		Objects.requireNonNull(launchFile, "launchFile");
		Objects.requireNonNull(contentFiles, "contentFiles");
		String launchHref = toPortableRelativePath(launchFile);
		List<String> fileHrefs = normaliseContentFiles(contentFiles);
		if (!fileHrefs.contains(launchHref)) {
			throw new IllegalArgumentException("The launch file must be included in the SCORM resource file list.");
		}
		Path normalisedPackageRoot = packageRoot.toAbsolutePath().normalize();
		Files.createDirectories(normalisedPackageRoot);
		Path manifestPath = normalisedPackageRoot.resolve(MANIFEST_FILE_NAME);
		try (BufferedWriter output = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8)) {
			XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
			writeManifest(xml, manifestIdentifier, title, launchHref, fileHrefs);
			xml.close();
		} catch (XMLStreamException exception) {
			throw new IOException("Could not write SCORM manifest.", exception);
		}
		return manifestPath;
	}

	private List<String> normaliseContentFiles(List<Path> contentFiles) {
		Set<String> sortedHrefs = new TreeSet<>();
		for (Path contentFile : contentFiles) {
			Objects.requireNonNull(contentFile, "contentFile");
			String href = toPortableRelativePath(contentFile);
			if (!sortedHrefs.add(href)) {
				throw new IllegalArgumentException("Duplicate SCORM resource file: " + href);
			}
		}
		return new ArrayList<>(sortedHrefs);
	}

	private void requireNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank.");
		}
	}

	private String toPortableRelativePath(Path path) {
		if (path.isAbsolute()) {
			throw new IllegalArgumentException("SCORM package paths must be relative: " + path);
		}
		Path normalised = path.normalize();
		if (normalised.getNameCount() == 0) {
			throw new IllegalArgumentException("SCORM package path must not be empty.");
		}
		if (normalised.startsWith("..")) {
			throw new IllegalArgumentException("SCORM package path must not escape the package root: " + path);
		}
		return normalised.toString().replace('\\', '/');
	}

	private void writeIndent(XMLStreamWriter xml, int level) throws XMLStreamException {
		xml.writeCharacters("\n" + "\t".repeat(level));
	}

	private void writeManifest(XMLStreamWriter xml, String manifestIdentifier, String title, String launchHref,
			List<String> fileHrefs) throws XMLStreamException {
		xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
		xml.writeCharacters("\n");
		xml.writeStartElement("", "manifest", IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeDefaultNamespace(IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeNamespace("adlcp", ADL_CONTENT_PACKAGING_NAMESPACE);
		xml.writeNamespace("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
		xml.writeAttribute("identifier", manifestIdentifier);
		xml.writeAttribute("version", "1");
		xml.writeAttribute("xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation", SCHEMA_LOCATION);
		writeMetadata(xml);
		writeOrganizations(xml, title);
		writeResources(xml, launchHref, fileHrefs);
		xml.writeCharacters("\n");
		xml.writeEndElement();
		xml.writeCharacters("\n");
		xml.writeEndDocument();
	}

	private void writeMetadata(XMLStreamWriter xml) throws XMLStreamException {
		writeIndent(xml, 1);
		xml.writeStartElement("", "metadata", IMS_CONTENT_PACKAGING_NAMESPACE);
		writeIndent(xml, 2);
		writeTextElement(xml, "schema", "ADL SCORM");
		writeIndent(xml, 2);
		writeTextElement(xml, "schemaversion", "1.2");
		writeIndent(xml, 1);
		xml.writeEndElement();
	}

	private void writeOrganizations(XMLStreamWriter xml, String title) throws XMLStreamException {
		writeIndent(xml, 1);
		xml.writeStartElement("", "organizations", IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeAttribute("default", ORGANIZATION_IDENTIFIER);
		writeIndent(xml, 2);
		xml.writeStartElement("", "organization", IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeAttribute("identifier", ORGANIZATION_IDENTIFIER);
		writeIndent(xml, 3);
		writeTextElement(xml, "title", title);
		writeIndent(xml, 3);
		xml.writeStartElement("", "item", IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeAttribute("identifier", ITEM_IDENTIFIER);
		xml.writeAttribute("identifierref", RESOURCE_IDENTIFIER);
		writeIndent(xml, 4);
		writeTextElement(xml, "title", title);
		writeIndent(xml, 3);
		xml.writeEndElement();
		writeIndent(xml, 2);
		xml.writeEndElement();
		writeIndent(xml, 1);
		xml.writeEndElement();
	}

	private void writeResources(XMLStreamWriter xml, String launchHref, List<String> fileHrefs)
			throws XMLStreamException {
		writeIndent(xml, 1);
		xml.writeStartElement("", "resources", IMS_CONTENT_PACKAGING_NAMESPACE);
		writeIndent(xml, 2);
		xml.writeStartElement("", "resource", IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeAttribute("identifier", RESOURCE_IDENTIFIER);
		xml.writeAttribute("type", "webcontent");
		xml.writeAttribute("adlcp", ADL_CONTENT_PACKAGING_NAMESPACE, "scormtype", "sco");
		xml.writeAttribute("href", launchHref);
		for (String fileHref : fileHrefs) {
			writeIndent(xml, 3);
			xml.writeEmptyElement("", "file", IMS_CONTENT_PACKAGING_NAMESPACE);
			xml.writeAttribute("href", fileHref);
		}
		writeIndent(xml, 2);
		xml.writeEndElement();
		writeIndent(xml, 1);
		xml.writeEndElement();
	}

	private void writeTextElement(XMLStreamWriter xml, String elementName, String text) throws XMLStreamException {
		xml.writeStartElement("", elementName, IMS_CONTENT_PACKAGING_NAMESPACE);
		xml.writeCharacters(text);
		xml.writeEndElement();
	}
}
