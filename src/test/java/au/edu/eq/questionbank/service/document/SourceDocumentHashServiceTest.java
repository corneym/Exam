package au.edu.eq.questionbank.service.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDocumentHashServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void calculatesCanonicalSha256FromFileBytes() throws Exception {
		Path source = tempDirectory.resolve("source.pdf");
		Files.writeString(source, "abc");
		String hash = new SourceDocumentHashService().sha256(source);

		// This is the published SHA-256 test vector for the three bytes "abc".
		assertEquals("ba7816bf8f01cfea414140de5dae2223" + "b00361a396177a9cb410ff61f20015ad", hash);
	}

	@Test
	void differentBytesHaveDifferentHashes() throws Exception {
		Path first = tempDirectory.resolve("paper1.pdf");
		Path second = tempDirectory.resolve("paper2.pdf");
		Files.writeString(first, "first examination contents");
		Files.writeString(second, "second examination contents");
		SourceDocumentHashService service = new SourceDocumentHashService();
		assertNotEquals(service.sha256(first), service.sha256(second));
	}

	@Test
	void identicalBytesHaveSameHashRegardlessOfFilename() throws Exception {
		Path first = tempDirectory.resolve("paper1.pdf");
		Path second = tempDirectory.resolve("renamed-copy.pdf");
		Files.writeString(first, "same examination contents");
		Files.writeString(second, "same examination contents");
		SourceDocumentHashService service = new SourceDocumentHashService();

		// Content identity must not depend on the source filename or stored path.
		assertEquals(service.sha256(first), service.sha256(second));
	}

	@Test
	void rejectsMissingAndNonFileSources() throws Exception {
		Path missing = tempDirectory.resolve("missing.pdf");
		Path directory = tempDirectory.resolve("directory.pdf");
		Files.createDirectories(directory);
		SourceDocumentHashService service = new SourceDocumentHashService();
		assertThrows(IOException.class, () -> service.sha256(missing));
		assertThrows(IOException.class, () -> service.sha256(directory));
	}

	@Test
	void rejectsNullSourcePath() {
		assertThrows(NullPointerException.class, () -> new SourceDocumentHashService().sha256(null));
	}
}
