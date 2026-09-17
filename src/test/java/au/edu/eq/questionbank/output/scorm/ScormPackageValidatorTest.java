package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScormPackageValidatorTest {

	@TempDir
	Path tempDir;

	@Test
	void acceptsCompleteSingleScoPackage() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		ScormPackageValidator validator = new ScormPackageValidator();
		validator.validate(packageRoot);
	}

	@Test
	void rejectsContentFileMissingFromManifest() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		writeFile(packageRoot.resolve(Path.of("assets", "questions", "question-999.png")), "undeclared image");
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsDeclaredFileThatDoesNotExist() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Files.delete(packageRoot.resolve(Path.of("assets", "revision.css")));
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsDefaultOrganizationThatDoesNotExist() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Path manifestPath = packageRoot.resolve("imsmanifest.xml");
		String manifest = Files.readString(manifestPath);
		manifest = manifest.replace("default=\"organization_1\"", "default=\"missing_organization\"");
		Files.writeString(manifestPath, manifest);
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsDuplicateFileDeclaration() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Path manifestPath = packageRoot.resolve("imsmanifest.xml");
		String manifest = Files.readString(manifestPath);
		manifest = manifest.replace("</resource>", "<file href=\"index.html\"/></resource>");
		Files.writeString(manifestPath, manifest);
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsDuplicateManifestIdentifier() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Path manifestPath = packageRoot.resolve("imsmanifest.xml");
		String manifest = Files.readString(manifestPath);
		manifest = manifest.replace("identifier=\"item_1\"", "identifier=\"resource_1\"");
		Files.writeString(manifestPath, manifest);
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsMalformedManifestXml() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("index.html"), "<html></html>");
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest>");
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsManifestContainingExternalEntityDeclaration() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Path externalContent = tempDir.resolve("external-content.txt");
		Files.writeString(externalContent, "content that must not be resolved");
		Path manifestPath = packageRoot.resolve("imsmanifest.xml");
		String manifest = Files.readString(manifestPath);
		String documentType = "<!DOCTYPE manifest [<!ENTITY external SYSTEM \""
				+ externalContent.toUri().toASCIIString() + "\">]>";
		manifest = manifest.replace("?>", "?>\n" + documentType);
		manifest = manifest.replace("Chemistry Revision", "&external;");
		Files.writeString(manifestPath, manifest);
		ScormPackageValidator validator = new ScormPackageValidator();
		IOException exception = assertThrows(IOException.class, () -> validator.validate(packageRoot));
		assertTrue(exception.getMessage().contains("SCORM manifest is not valid XML"));
	}

	@Test
	void rejectsMissingManifest() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("index.html"), "<html></html>");
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsMissingScormSchemaSupportFile() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Files.delete(packageRoot.resolve("ims_xml.xsd"));
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsSourcePdfEvenWhenDeclared() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("index.html"), "<html></html>");
		writeFile(packageRoot.resolve(Path.of("assets", "revision.css")), "body {}");
		writeFile(packageRoot.resolve("source-exam.pdf"), "not a real PDF");
		ScormManifestWriter writer = new ScormManifestWriter();
		writer.write(packageRoot, "au.edu.eq.questionbank.chemistry", "Chemistry Revision", Path.of("index.html"),
				List.of(Path.of("index.html"), Path.of("assets", "revision.css"), Path.of("source-exam.pdf")));
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsUnexpectedUndeclaredSchemaFile() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		writeFile(packageRoot.resolve("unexpected.xsd"), "unexpected schema");
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	@Test
	void rejectsUnsafeManifestReference() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		createValidPackage(packageRoot);
		Path manifestPath = packageRoot.resolve("imsmanifest.xml");
		String manifest = Files.readString(manifestPath);
		manifest = manifest.replace("href=\"assets/revision.css\"", "href=\"../outside.css\"");
		Files.writeString(manifestPath, manifest);
		ScormPackageValidator validator = new ScormPackageValidator();
		assertThrows(IOException.class, () -> validator.validate(packageRoot));
	}

	private void createValidPackage(Path packageRoot) throws Exception {
		writeFile(packageRoot.resolve("index.html"), "<html></html>");
		writeFile(packageRoot.resolve(Path.of("assets", "revision.css")), "body {}");
		writeFile(packageRoot.resolve(Path.of("assets", "questions", "question-1.png")), "question image");
		writeFile(packageRoot.resolve(Path.of("assets", "answers", "question-1-answer-01.png")), "answer image");
		writeFile(packageRoot.resolve(Path.of("units", "unit-10", "topic-11.html")), "<html></html>");
		/*
		 * SCORM schema/support files are package infrastructure, not SCO content. They
		 * therefore do not appear as <file> children of the resource.
		 */
		writeFile(packageRoot.resolve("adlcp_rootv1p2.xsd"), "schema support");
		writeFile(packageRoot.resolve("ims_xml.xsd"), "schema support");
		writeFile(packageRoot.resolve("imscp_rootv1p1p2.xsd"), "schema support");
		writeFile(packageRoot.resolve("imsmd_rootv1p2p1.xsd"), "schema support");
		ScormManifestWriter writer = new ScormManifestWriter();
		writer.write(packageRoot, "au.edu.eq.questionbank.chemistry", "Chemistry Revision", Path.of("index.html"),
				List.of(Path.of("index.html"), Path.of("assets", "revision.css"),
						Path.of("assets", "questions", "question-1.png"),
						Path.of("assets", "answers", "question-1-answer-01.png"),
						Path.of("units", "unit-10", "topic-11.html")));
	}

	private void writeFile(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
