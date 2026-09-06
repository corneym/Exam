package au.edu.eq.questionbank.output.scorm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScormZipWriterTest {

	@TempDir
	Path tempDir;

	private void createEquivalentPackage(Path packageRoot) throws IOException {
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest/>");
		writeFile(packageRoot.resolve("index.html"), "<html>Revision</html>");
		writeFile(packageRoot.resolve(Path.of("assets", "revision.css")), "body {}");
		writeFile(packageRoot.resolve(Path.of("assets", "questions", "question-1.png")), "question image");
	}

	@Test
	void doesNotAddPackageDirectoryAsArchiveWrapper() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest/>");
		writeFile(packageRoot.resolve("index.html"), "<html></html>");

		Path destination = tempDir.resolve("Chemistry.zip");

		new ScormZipWriter().write(packageRoot, destination);

		try (ZipFile zipFile = new ZipFile(destination.toFile(), StandardCharsets.UTF_8)) {
			assertTrue(zipFile.getEntry("imsmanifest.xml") != null);
			assertTrue(zipFile.getEntry("index.html") != null);
			assertTrue(zipFile.getEntry("package/imsmanifest.xml") == null);
			assertTrue(zipFile.getEntry("package/index.html") == null);
		}
	}

	@Test
	void producesIdenticalZipForIdenticalPackageContents() throws Exception {
		Path firstPackage = tempDir.resolve("first-package");
		Path secondPackage = tempDir.resolve("second-package");

		createEquivalentPackage(firstPackage);
		createEquivalentPackage(secondPackage);

		Path firstZip = tempDir.resolve("first.zip");
		Path secondZip = tempDir.resolve("second.zip");

		ScormZipWriter writer = new ScormZipWriter();

		writer.write(firstPackage, firstZip);
		writer.write(secondPackage, secondZip);

		assertArrayEquals(Files.readAllBytes(firstZip), Files.readAllBytes(secondZip));
	}

	@Test
	void rejectsDestinationInsidePackageRoot() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest/>");

		Path destination = packageRoot.resolve("Chemistry.zip");

		ScormZipWriter writer = new ScormZipWriter();

		assertThrows(IOException.class, () -> writer.write(packageRoot, destination));
		assertFalse(Files.exists(destination));
	}

	@Test
	void rejectsEmptyPackageWithoutPublishingZip() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		Files.createDirectories(packageRoot);

		Path destination = tempDir.resolve("Chemistry.zip");

		ScormZipWriter writer = new ScormZipWriter();

		assertThrows(IOException.class, () -> writer.write(packageRoot, destination));
		assertFalse(Files.exists(destination));
	}

	@Test
	void rejectsExistingDestinationWithoutChangingIt() throws Exception {
		Path packageRoot = tempDir.resolve("package");
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest/>");

		Path destination = tempDir.resolve("Chemistry.zip");
		byte[] existingContent = "existing ZIP placeholder".getBytes(StandardCharsets.UTF_8);
		Files.write(destination, existingContent);

		ScormZipWriter writer = new ScormZipWriter();

		assertThrows(IOException.class, () -> writer.write(packageRoot, destination));

		assertArrayEquals(existingContent, Files.readAllBytes(destination));
	}

	private void writeFile(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	@Test
	void writesPackageFilesAtZipRootInDeterministicPortableOrder() throws Exception {
		Path packageRoot = tempDir.resolve("package");

		writeFile(packageRoot.resolve("index.html"), "<html></html>");
		writeFile(packageRoot.resolve("imsmanifest.xml"), "<manifest/>");
		writeFile(packageRoot.resolve(Path.of("assets", "revision.css")), "body {}");
		writeFile(packageRoot.resolve(Path.of("units", "unit-10", "topic-11.html")), "<html></html>");

		Path destination = tempDir.resolve("Chemistry.zip");

		ScormZipWriter writer = new ScormZipWriter();
		Path result = writer.write(packageRoot, destination);

		assertEquals(destination.toAbsolutePath().normalize(), result);
		assertTrue(Files.isRegularFile(destination));

		try (ZipFile zipFile = new ZipFile(destination.toFile(), StandardCharsets.UTF_8)) {
			List<String> entryNames = new ArrayList<String>();
			Enumeration<? extends ZipEntry> entries = zipFile.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				entryNames.add(entry.getName());

				assertEquals(0L, entry.getTime());
				assertFalse(entry.getName().contains("\\"));
				assertFalse(entry.getName().startsWith("/"));
			}

			assertEquals(List.of("assets/revision.css", "imsmanifest.xml", "index.html", "units/unit-10/topic-11.html"),
					entryNames);
		}
	}
}
