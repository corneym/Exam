package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentManagedAssetGate.Disposition;

class WorkAssignmentAssetExtractorTest {

	@TempDir
	Path directory;

	@Test
	void changedExtractedAssetFailsReverification() throws Exception {
		var staged = stage();
		var extracted = extractor().extract(staged);
		var asset = extracted.assets().getFirst();
		Path verified = extracted.requireVerifiedAsset(asset.archivePath());
		Files.writeString(verified, "Changed asset bytes");
		assertThrows(IOException.class, () -> extracted.requireVerifiedAsset(asset.archivePath()));
		stager().discard(staged);
	}

	@Test
	void changedManagedPdfPreventsExtraction() throws Exception {
		var staged = stage();
		Path destination = directory.resolve(WorkAssignmentArchiveFixture.ANSWER_PATH);
		Files.createDirectories(destination.getParent());
		byte[] original = "Different PDF bytes".getBytes(StandardCharsets.UTF_8);
		Files.write(destination, original);
		IOException failure = assertThrows(IOException.class, () -> extractor().extract(staged));
		assertTrue(failure.getMessage().contains("different bytes"));
		assertArrayEquals(original, Files.readAllBytes(destination));
		assertFalse(Files.exists(staged.directory().resolve("assets")));
		stager().discard(staged);
	}

	@Test
	void extractsCompleteSharedBookletAssetsWithoutPublication() throws Exception {
		var staged = stage();
		var extracted = extractor().extract(staged);
		assertEquals(2, extracted.assets().size());
		assertEquals(2, extracted.destinations().size());
		assertTrue(
				extracted.destinations().stream().allMatch(decision -> decision.disposition() == Disposition.CREATE));
		for (var reference : staged.inspection().assets().references()) {
			Path file = extracted.requireVerifiedAsset(reference.archivePath());
			byte[] expected = reference.managedPath().equals(WorkAssignmentArchiveFixture.QUESTION_PATH)
					? WorkAssignmentArchiveFixture.QUESTION_BYTES
					: WorkAssignmentArchiveFixture.ANSWER_BYTES;
			assertArrayEquals(expected, Files.readAllBytes(file));
			assertTrue(file.startsWith(staged.directory()));
		}

		// Both Booklets share one source document. The archive
		// contains only its one physical PDF asset.
		long booklets = staged.inspection().records().stream()
				.filter(record -> record.recordType().equals("EXAM_BOOKLET")).count();
		assertEquals(2, booklets);
		assertFalse(Files.exists(directory.resolve(WorkAssignmentArchiveFixture.QUESTION_PATH)));
		assertFalse(Files.exists(directory.resolve(WorkAssignmentArchiveFixture.ANSWER_PATH)));
		stager().discard(staged);
	}

	@Test
	void modifiedPrivateArchiveIsRejectedBeforeExtraction() throws Exception {
		var staged = stage();
		Files.writeString(staged.archive(), "Modified private archive");
		assertThrows(IOException.class, () -> extractor().extract(staged));
		assertFalse(Files.exists(staged.directory().resolve("assets")));
		stager().discard(staged);
	}

	@Test
	void repeatExtractionDoesNotOverwritePrivateFiles() throws Exception {
		var staged = stage();
		var first = extractor().extract(staged);
		var asset = first.assets().getFirst();
		assertThrows(IOException.class, () -> extractor().extract(staged));
		assertTrue(Files.isRegularFile(first.requireVerifiedAsset(asset.archivePath())));
		stager().discard(staged);
	}

	private WorkAssignmentAssetExtractor extractor() {
		return new WorkAssignmentAssetExtractor(ApplicationConfig.fromDataRoot(directory));
	}

	private WorkAssignmentArchiveStager.StagedAssignment stage() throws Exception {
		Path incoming = WorkAssignmentArchiveFixture.write(directory.resolve("assignment.examwork"));
		return stager().stage(incoming);
	}

	private WorkAssignmentArchiveStager stager() {
		return new WorkAssignmentArchiveStager(ApplicationConfig.fromDataRoot(directory));
	}
}
