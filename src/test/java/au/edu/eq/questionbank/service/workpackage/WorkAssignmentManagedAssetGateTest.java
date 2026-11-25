package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.WorkAssignmentManagedAssetGate.Disposition;

class WorkAssignmentManagedAssetGateTest {

	@TempDir
	Path directory;

	@Test
	void acceptedExamPdfDestinationsMatchPdfStoreResolution() throws Exception {
		byte[] bytes = content("Coordinator examination PDF");
		ManagedDataLayout layout = new ManagedDataLayout(directory);
		PdfStore canonicalStore = new PdfStore(layout);
		PdfStore transitionalStore = new PdfStore(layout, directory.resolve("pdf"));
		List<String> paths = List.of("subjects/Biology/exams/questions.pdf", "subjects/Biology/exams/answers.pdf",
				"subjects/Biology/exams/QCAA/2025/" + "External Assessment/questions.pdf");
		for (String path : paths) {
			Reference reference = pdf(path, bytes);
			ExamWorkAssetIndex index = new ExamWorkAssetIndex(List.of(reference));
			var decision = gate().inspect("Biology", index).getFirst();
			assertEquals(Disposition.CREATE, decision.disposition());
			assertEquals(decision.destination(), canonicalStore.resolve(path));
			assertEquals(decision.destination(), transitionalStore.resolve(path));
			assertFalse(Files.exists(decision.destination()));

			// Simulate completed managed publication, then
			// verify that subsequent PDF access and assignment
			// reuse identify the same physical file.
			Files.createDirectories(decision.destination().getParent());
			Files.write(decision.destination(), bytes);
			var reused = gate().inspect("Biology", index).getFirst();
			assertEquals(Disposition.REUSE, reused.disposition());
			assertArrayEquals(bytes, Files.readAllBytes(canonicalStore.resolve(path)));
			assertArrayEquals(bytes, Files.readAllBytes(transitionalStore.resolve(path)));
		}
	}

	@Test
	void embeddedImageNeverBecomesAManagedFile() throws Exception {
		byte[] png = new byte[] { 1, 2, 3, 4 };
		String hash = sha256(png);
		UUID owner = UUID.randomUUID();
		Reference reference = new Reference("assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".png", hash,
				png.length, "image/png",
				"subjects/Biology/exams/_examwork_embedded/" + "QUESTION_IMAGE/" + owner + ".png", "QUESTION_IMAGE",
				owner, "EMBEDDED_QUESTION_PNG");
		var decisions = gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference)));
		assertEquals(Disposition.EMBEDDED, decisions.getFirst().disposition());
		assertNull(decisions.getFirst().destination());
		assertFalse(Files.exists(directory.resolve(reference.managedPath())));
	}

	@Test
	void identicalExistingPdfCanBeReusedAcrossAssignments() throws Exception {
		byte[] bytes = content("Original coordinator PDF");
		Reference reference = pdf("subjects/Biology/exams/questions.pdf", bytes);
		Path destination = directory.resolve(reference.managedPath());
		Files.createDirectories(destination.getParent());
		Files.write(destination, bytes);
		var result = gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference)));
		assertEquals(1, result.size());
		assertEquals(Disposition.REUSE, result.getFirst().disposition());
		assertEquals(destination.toAbsolutePath().normalize(), result.getFirst().destination());
		assertArrayEquals(bytes, Files.readAllBytes(destination));
	}

	@Test
	void newPdfRequiresCreationButIsNotPublished() throws Exception {
		byte[] bytes = content("New coordinator PDF");
		Reference reference = pdf("subjects/Biology/exams/questions.pdf", bytes);
		Path destination = directory.resolve(reference.managedPath());
		var result = gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference)));
		assertEquals(Disposition.CREATE, result.getFirst().disposition());
		assertEquals(destination.toAbsolutePath().normalize(), result.getFirst().destination());
		assertFalse(Files.exists(destination));
	}

	@Test
	void rejectsAssetsFromAnotherSubject() throws Exception {
		Reference reference = pdf("subjects/Chemistry/exams/questions.pdf", content("Chemistry examination"));
		assertThrows(IOException.class, () -> gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference))));
	}

	@Test
	void rejectsChangedExistingPdfWithoutOverwritingIt() throws Exception {
		byte[] original = content("Modified local PDF");
		byte[] issued = content("Original coordinator PDF");
		Reference reference = pdf("subjects/Biology/exams/questions.pdf", issued);
		Path destination = directory.resolve(reference.managedPath());
		Files.createDirectories(destination.getParent());
		Files.write(destination, original);
		IOException failure = assertThrows(IOException.class,
				() -> gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference))));
		assertTrue(failure.getMessage().contains("different bytes"));
		assertArrayEquals(original, Files.readAllBytes(destination));
	}

	@Test
	void rejectsExamPdfOutsideExamNamespace() throws Exception {
		byte[] bytes = content("Coordinator examination PDF");
		for (String path : List.of("subjects/Biology/questions.pdf", "subjects/Biology/curriculum/questions.pdf",
				"subjects/Biology/legacy/questions.pdf")) {
			Reference reference = pdf(path, bytes);
			IOException failure = assertThrows(IOException.class,
					() -> gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference))));
			assertTrue(failure.getMessage().contains("Invalid assignment Exam PDF destination"));
			assertFalse(Files.exists(directory.resolve(path)));
		}
	}

	@Test
	void rejectsNonDirectoryAncestor() throws Exception {
		Path subjects = directory.resolve("subjects");
		Files.writeString(subjects, "Not a directory");
		Reference reference = pdf("subjects/Biology/exams/questions.pdf", content("Coordinator PDF"));
		assertThrows(IOException.class, () -> gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference))));
		assertEquals("Not a directory", Files.readString(subjects));
	}

	@Test
	void rejectsSymbolicLinkAncestor() throws Exception {
		Path outside = directory.resolveSibling(directory.getFileName() + "-outside");
		Files.createDirectories(outside);
		Files.createDirectories(directory.resolve("subjects"));
		Path link = directory.resolve("subjects/Biology");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (IOException | UnsupportedOperationException | SecurityException exception) {
			Assumptions.assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
		}
		Reference reference = pdf("subjects/Biology/exams/questions.pdf", content("Coordinator PDF"));
		assertThrows(IOException.class, () -> gate().inspect("Biology", new ExamWorkAssetIndex(List.of(reference))));
	}

	@Test
	void sharedAnswerPdfProducesCompatibleDecisions() throws Exception {
		byte[] bytes = content("Shared answer PDF");
		String path = "subjects/Biology/exams/answers.pdf";
		Reference first = pdf(path, bytes);
		Reference second = pdf(path, bytes);

		// Two logical owners may use one immutable managed file.
		second = new Reference(second.archivePath(), second.sha256(), second.size(), second.mediaType(),
				second.managedPath(), second.ownerType(), UUID.randomUUID(), second.usage());
		var index = new ExamWorkAssetIndex(List.of(first, second));
		var decisions = gate().inspect("Biology", index);
		assertEquals(2, decisions.size());
		assertTrue(decisions.stream().allMatch(decision -> decision.disposition() == Disposition.CREATE));
		assertEquals(decisions.get(0).destination(), decisions.get(1).destination());
		assertFalse(Files.exists(directory.resolve(path)));
	}

	@Test
	void syllabusPdfRetainsIndependentManagedDestination() throws Exception {
		byte[] bytes = content("Coordinator syllabus PDF");
		String hash = sha256(bytes);
		String path = "subjects/Biology/curriculum/2025/" + "sources/syllabus.pdf";
		Reference reference = new Reference("assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", hash,
				bytes.length, "application/pdf", path, "SYLLABUS_VERSION", UUID.randomUUID(), "SYLLABUS_PDF");
		ExamWorkAssetIndex index = new ExamWorkAssetIndex(List.of(reference));
		var decision = gate().inspect("Biology", index).getFirst();
		assertEquals(Disposition.CREATE, decision.disposition());
		assertEquals(directory.resolve(path).toAbsolutePath().normalize(), decision.destination());
		assertFalse(Files.exists(decision.destination()));
		Files.createDirectories(decision.destination().getParent());
		Files.write(decision.destination(), bytes);
		var repeated = gate().inspect("Biology", index).getFirst();
		assertEquals(Disposition.REUSE, repeated.disposition());
		assertArrayEquals(bytes, Files.readAllBytes(repeated.destination()));
	}

	private byte[] content(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private WorkAssignmentManagedAssetGate gate() throws IOException {
		Files.createDirectories(directory);
		return new WorkAssignmentManagedAssetGate(ApplicationConfig.fromDataRoot(directory));
	}

	private Reference pdf(String path, byte[] bytes) throws Exception {
		String hash = sha256(bytes);
		return new Reference("assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", hash, bytes.length,
				"application/pdf", path, "SOURCE_DOCUMENT", UUID.randomUUID(), "SOURCE_PDF");
	}

	private String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
