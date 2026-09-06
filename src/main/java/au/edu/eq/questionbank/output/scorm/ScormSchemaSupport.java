package au.edu.eq.questionbank.output.scorm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ScormSchemaSupport {

	private static final String RESOURCE_ROOT = "/au/edu/eq/questionbank/output/scorm/schema/";

	static final List<String> REQUIRED_SCHEMA_FILES = List.of("adlcp_rootv1p2.xsd", "ims_xml.xsd",
			"imscp_rootv1p1p2.xsd", "imsmd_rootv1p2p1.xsd");

	private void copySchemaFile(Path packageRoot, String fileName) throws IOException {
		String resourceName = RESOURCE_ROOT + fileName;

		try (InputStream input = ScormSchemaSupport.class.getResourceAsStream(resourceName)) {
			if (input == null) {
				throw new IOException("SCORM schema resource is missing: " + resourceName);
			}

			Files.copy(input, packageRoot.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public void copyTo(Path packageRoot) throws IOException {
		if (packageRoot == null) {
			throw new NullPointerException("packageRoot");
		}

		Path root = packageRoot.toAbsolutePath().normalize();
		Files.createDirectories(root);

		for (String fileName : REQUIRED_SCHEMA_FILES) {
			copySchemaFile(root, fileName);
		}
	}
}
