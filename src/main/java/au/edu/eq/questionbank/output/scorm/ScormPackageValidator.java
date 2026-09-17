package au.edu.eq.questionbank.output.scorm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Validates a generated SCORM 1.2 package directory before it is written to a
 * final ZIP file.
 */
public final class ScormPackageValidator {

	/**
	 * Creates a validator for staged SCORM package contents.
	 */
	public ScormPackageValidator() {
	}

	/**
	 * Validates the SCORM profile, package structure, references and exact
	 * learning-content inventory of a staged package.
	 *
	 * @param packageRoot root directory of the staged package
	 * @throws IOException          if the package is missing, malformed, unsafe or
	 *                              internally inconsistent
	 * @throws NullPointerException if {@code packageRoot} is null
	 */
	public void validate(Path packageRoot) throws IOException {
		if (packageRoot == null) {
			throw new NullPointerException("packageRoot");
		}
		Path root = packageRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("SCORM package root does not exist: " + root);
		}
		Path manifestPath = root.resolve(ScormManifestWriter.MANIFEST_FILE_NAME);
		validateNonEmptyFile(manifestPath, "SCORM manifest");
		validateScormSupportFiles(root);
		Document document = parseManifest(manifestPath);
		validateManifestRoot(document);
		validateProfileMetadata(document);
		validateUniqueIdentifiers(document);
		Element resource = validateSingleScoStructure(document);
		Set<String> declaredContentFiles = validateResourceFiles(root, resource);
		validateActualContentInventory(root, declaredContentFiles);
	}

	private Set<String> collectActualContentFiles(Path root) throws IOException {
		List<Path> packageFiles;
		try (Stream<Path> stream = Files.walk(root)) {
			packageFiles = stream.filter(path -> Files.isRegularFile(path)).toList();
		}
		Set<String> actualContentFiles = new HashSet<String>();
		for (Path file : packageFiles) {
			String relativePath = root.relativize(file).toString().replace(File.separatorChar, '/');
			if (ScormManifestWriter.MANIFEST_FILE_NAME.equals(relativePath)) {
				continue;
			}
			if (isScormSupportFile(relativePath)) {
				continue;
			}
			if (relativePath.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
				throw new IOException("Source PDF must not be included in the SCORM package: " + relativePath);
			}
			actualContentFiles.add(relativePath);
		}
		return actualContentFiles;
	}

	private boolean isScormSupportFile(String relativePath) {
		return ScormSchemaSupport.REQUIRED_SCHEMA_FILES.contains(relativePath);
	}

	private Document parseManifest(Path manifestPath) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new ErrorHandler() {

				@Override
				public void error(SAXParseException exception) throws SAXException {
					throw exception;
				}

				@Override
				public void fatalError(SAXParseException exception) throws SAXException {
					throw exception;
				}

				@Override
				public void warning(SAXParseException exception) throws SAXException {
					throw exception;
				}
			});
			return builder.parse(manifestPath.toFile());
		} catch (ParserConfigurationException | SAXException exception) {
			throw new IOException("SCORM manifest is not valid XML: " + manifestPath, exception);
		}
	}

	private String requireAttribute(Element element, String attributeName) throws IOException {
		String value = element.getAttribute(attributeName);
		if (value == null || value.isBlank()) {
			throw new IOException(
					"SCORM manifest element <" + element.getLocalName() + "> is missing attribute " + attributeName);
		}
		return value;
	}

	private String requireAttribute(Element element, String namespace, String attributeName) throws IOException {
		String value = element.getAttributeNS(namespace, attributeName);
		if (value == null || value.isBlank()) {
			throw new IOException(
					"SCORM manifest element <" + element.getLocalName() + "> is missing attribute " + attributeName);
		}
		return value;
	}

	private Element requireSingleElement(Document document, String localName) throws IOException {
		NodeList elements = document.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				localName);
		if (elements.getLength() != 1) {
			throw new IOException("SCORM manifest must contain exactly one <" + localName + "> element but found "
					+ elements.getLength());
		}
		return (Element) elements.item(0);
	}

	private String requireSingleTextElement(Document document, String localName) throws IOException {
		Element element = requireSingleElement(document, localName);
		String text = element.getTextContent();
		if (text == null || text.isBlank()) {
			throw new IOException("SCORM manifest element <" + localName + "> must not be blank");
		}
		return text.trim();
	}

	private Path resolveContentReference(Path root, String reference) throws IOException {
		validatePortableReference(reference);
		try {
			Path relativePath = Path.of(reference.replace('/', File.separatorChar)).normalize();
			Path resolved = root.resolve(relativePath).normalize();
			if (!resolved.startsWith(root)) {
				throw new IOException("SCORM manifest reference escapes the package root: " + reference);
			}
			return resolved;
		} catch (InvalidPathException exception) {
			throw new IOException("SCORM manifest contains an invalid path: " + reference, exception);
		}
	}

	private void validateActualContentInventory(Path root, Set<String> declaredContentFiles) throws IOException {
		Set<String> actualContentFiles = collectActualContentFiles(root);
		if (actualContentFiles.equals(declaredContentFiles)) {
			return;
		}
		Set<String> undeclaredFiles = new TreeSet<String>(actualContentFiles);
		undeclaredFiles.removeAll(declaredContentFiles);
		Set<String> missingFiles = new TreeSet<String>(declaredContentFiles);
		missingFiles.removeAll(actualContentFiles);
		throw new IOException("SCORM manifest content inventory does not match package contents. Undeclared files: "
				+ undeclaredFiles + "; missing files: " + missingFiles);
	}

	private void validateManifestRoot(Document document) throws IOException {
		Element manifest = document.getDocumentElement();
		if (manifest == null || !"manifest".equals(manifest.getLocalName())
				|| !ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE.equals(manifest.getNamespaceURI())) {
			throw new IOException("SCORM manifest root must be a SCORM 1.2 IMS <manifest> element");
		}
		requireAttribute(manifest, "identifier");
	}

	private void validateNonEmptyFile(Path file, String description) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException(description + " does not exist: " + file);
		}
		if (Files.size(file) == 0) {
			throw new IOException(description + " is empty: " + file);
		}
	}

	private void validatePortableReference(String reference) throws IOException {
		if (reference == null || reference.isBlank()) {
			throw new IOException("SCORM manifest contains an empty file reference");
		}
		String lowerReference = reference.toLowerCase(Locale.ROOT);
		if (reference.startsWith("/") || reference.startsWith("\\") || reference.contains("\\")
				|| reference.matches("^[A-Za-z]:.*") || lowerReference.matches("^[a-z][a-z0-9+.-]*:.*")) {
			throw new IOException("SCORM manifest contains an absolute or external reference: " + reference);
		}
		if (reference.contains("#") || reference.contains("?")) {
			throw new IOException("SCORM manifest reference must be a plain relative file path: " + reference);
		}
		try {
			Path path = Path.of(reference).normalize();
			if (path.isAbsolute() || path.startsWith("..")) {
				throw new IOException("SCORM manifest reference escapes the package root: " + reference);
			}
		} catch (InvalidPathException exception) {
			throw new IOException("SCORM manifest contains an invalid path: " + reference, exception);
		}
	}

	private void validateProfileMetadata(Document document) throws IOException {
		String schema = requireSingleTextElement(document, "schema");
		String schemaVersion = requireSingleTextElement(document, "schemaversion");
		if (!"ADL SCORM".equals(schema)) {
			throw new IOException("SCORM manifest schema must be ADL SCORM but was: " + schema);
		}
		if (!"1.2".equals(schemaVersion)) {
			throw new IOException("SCORM manifest schema version must be 1.2 but was: " + schemaVersion);
		}
	}

	private Set<String> validateResourceFiles(Path root, Element resource) throws IOException {
		String launchReference = requireAttribute(resource, "href");
		resolveContentReference(root, launchReference);
		NodeList fileNodes = resource.getElementsByTagNameNS(ScormManifestWriter.IMS_CONTENT_PACKAGING_NAMESPACE,
				"file");
		if (fileNodes.getLength() == 0) {
			throw new IOException("SCORM resource must declare at least one content file");
		}
		Set<String> declaredFiles = new HashSet<String>();
		for (int index = 0; index < fileNodes.getLength(); index++) {
			Element fileElement = (Element) fileNodes.item(index);
			String reference = requireAttribute(fileElement, "href");
			resolveContentReference(root, reference);
			if (!declaredFiles.add(reference)) {
				throw new IOException("SCORM resource contains duplicate file declaration: " + reference);
			}
			Path contentFile = resolveContentReference(root, reference);
			validateNonEmptyFile(contentFile, "SCORM resource file");
		}
		if (!declaredFiles.contains(launchReference)) {
			throw new IOException("SCORM launch file is not declared in the resource: " + launchReference);
		}
		return declaredFiles;
	}

	private void validateScormSupportFiles(Path root) throws IOException {
		for (String fileName : ScormSchemaSupport.REQUIRED_SCHEMA_FILES) {
			validateNonEmptyFile(root.resolve(fileName), "SCORM schema support file");
		}
	}

	private Element validateSingleScoStructure(Document document) throws IOException {
		Element organizations = requireSingleElement(document, "organizations");
		Element organization = requireSingleElement(document, "organization");
		Element item = requireSingleElement(document, "item");
		Element resource = requireSingleElement(document, "resource");
		String defaultOrganization = requireAttribute(organizations, "default");
		String organizationIdentifier = requireAttribute(organization, "identifier");
		if (!defaultOrganization.equals(organizationIdentifier)) {
			throw new IOException(
					"SCORM default organization does not identify the packaged organization: " + defaultOrganization);
		}
		String resourceIdentifier = requireAttribute(resource, "identifier");
		String itemResourceIdentifier = requireAttribute(item, "identifierref");
		if (!resourceIdentifier.equals(itemResourceIdentifier)) {
			throw new IOException(
					"SCORM item identifierref does not identify the packaged resource: " + itemResourceIdentifier);
		}
		String resourceType = requireAttribute(resource, "type");
		if (!"webcontent".equals(resourceType)) {
			throw new IOException("SCORM resource type must be webcontent but was: " + resourceType);
		}
		String scormType = requireAttribute(resource, ScormManifestWriter.ADL_CONTENT_PACKAGING_NAMESPACE, "scormtype");
		if (!"sco".equals(scormType)) {
			throw new IOException("SCORM 1.2 resource scormtype must be sco but was: " + scormType);
		}
		return resource;
	}

	private void validateUniqueIdentifiers(Document document) throws IOException {
		NodeList elements = document.getElementsByTagName("*");
		Set<String> identifiers = new HashSet<String>();
		for (int index = 0; index < elements.getLength(); index++) {
			Element element = (Element) elements.item(index);
			if (!element.hasAttribute("identifier")) {
				continue;
			}
			String identifier = element.getAttribute("identifier");
			if (identifier == null || identifier.isBlank()) {
				throw new IOException("SCORM manifest element <" + element.getLocalName() + "> has a blank identifier");
			}
			if (!identifiers.add(identifier)) {
				throw new IOException("SCORM manifest contains duplicate identifier: " + identifier);
			}
		}
	}
}
