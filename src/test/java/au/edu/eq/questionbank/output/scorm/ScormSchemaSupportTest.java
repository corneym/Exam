package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScormSchemaSupportTest {

	@TempDir
	Path tempDir;

	private void assertNonEmpty(String fileName) throws Exception {
		Path file = tempDir.resolve(fileName);
		assertTrue(Files.isRegularFile(file));
		assertTrue(Files.size(file) > 0);
	}

	@Test
	void copiesAllScorm12SchemaFilesToPackageRoot() throws Exception {
		ScormSchemaSupport support = new ScormSchemaSupport();
		support.copyTo(tempDir);
		assertNonEmpty("adlcp_rootv1p2.xsd");
		assertNonEmpty("ims_xml.xsd");
		assertNonEmpty("imscp_rootv1p1p2.xsd");
		assertNonEmpty("imsmd_rootv1p2p1.xsd");
	}
}
