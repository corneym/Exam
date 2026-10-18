package au.edu.eq.questionbank.ui.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFilePickerTest {

	@TempDir
	Path dataRoot;

	@Test
	void acceptsNormalizedPathsInsideConfiguredRoot() {
		PdfFilePicker picker = new PdfFilePicker(dataRoot);
		assertTrue(picker.isInsideDataRoot(dataRoot.resolve("chemistry").resolve("..").resolve("exam.pdf")));
	}

	@Test
	void cancellationRetainsLastSuccessfulDirectory() throws Exception {
		Path sourceDirectory = Files.createDirectory(dataRoot.resolve("source"));
		Path questionPdf = Files.createFile(sourceDirectory.resolve("question.pdf"));
		Path answerPdf = Files.createFile(sourceDirectory.resolve("answer.pdf"));
		AtomicInteger invocation = new AtomicInteger();
		List<Path> initialDirectories = new ArrayList<>();
		PdfFilePicker picker = new PdfFilePicker(dataRoot, (chooser, _) -> {
			initialDirectories.add(chooser.getInitialDirectory().toPath());
			return switch (invocation.getAndIncrement()) {
			case 0 -> questionPdf.toFile();
			case 1 -> null;
			case 2 -> answerPdf.toFile();
			default -> throw new AssertionError("Unexpected chooser invocation");
			};
		});
		assertEquals(questionPdf, picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		assertNull(picker.chooseAnyPdf(null, "Choose Answer booklet PDF"));
		assertEquals(answerPdf, picker.chooseAnyPdf(null, "Choose Answer booklet PDF"));
		assertEquals(List.of(dataRoot, sourceDirectory, sourceDirectory), initialDirectories);
	}

	@Test
	void missingRememberedDirectoryFallsBackToDataRoot() throws Exception {
		Path sourceDirectory = Files.createDirectory(dataRoot.resolve("temporary"));
		Path questionPdf = Files.createFile(sourceDirectory.resolve("question.pdf"));
		AtomicInteger invocation = new AtomicInteger();
		AtomicReference<File> fallbackDirectory = new AtomicReference<>();
		PdfFilePicker picker = new PdfFilePicker(dataRoot, (chooser, _) -> {
			if (invocation.getAndIncrement() == 0) {
				return questionPdf.toFile();
			}
			fallbackDirectory.set(chooser.getInitialDirectory());
			return null;
		});
		assertEquals(questionPdf, picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		Files.delete(questionPdf);
		Files.delete(sourceDirectory);
		assertNull(picker.chooseAnyPdf(null, "Choose Answer booklet PDF"));
		assertEquals(dataRoot.toFile(), fallbackDirectory.get());
	}

	@Test
	void missingRootDoesNotSetInvalidInitialDirectory() {
		Path missingRoot = dataRoot.resolve("not-created");
		AtomicReference<File> initialDirectory = new AtomicReference<>();
		PdfFilePicker picker = new PdfFilePicker(missingRoot, (chooser, _) -> {
			initialDirectory.set(chooser.getInitialDirectory());
			return null;
		});
		assertNull(picker.chooseAnyPdf(null, "Choose Question booklet PDF"));

		// A null initial directory lets the native chooser use
		// its normal platform-selected starting directory.
		assertNull(initialDirectory.get());
	}

	@Test
	void preferredWorkbookDirectoryIsUsedOnceAndPdfHistoryResumes() throws Exception {
		Path previousDirectory = Files.createDirectory(dataRoot.resolve("previous"));
		Path workbookDirectory = Files.createDirectory(dataRoot.resolve("workbooks"));
		Path pdfDirectory = Files.createDirectory(dataRoot.resolve("pdfs"));
		Path previousPdf = Files.createFile(previousDirectory.resolve("previous.pdf"));
		Path selectedPdf = Files.createFile(pdfDirectory.resolve("mcq.pdf"));
		AtomicInteger invocation = new AtomicInteger();
		List<Path> initialDirectories = new ArrayList<>();
		PdfFilePicker picker = new PdfFilePicker(dataRoot, (chooser, _) -> {
			initialDirectories.add(chooser.getInitialDirectory().toPath());
			return switch (invocation.getAndIncrement()) {
			case 0 -> previousPdf.toFile();
			case 1 -> null;
			case 2 -> selectedPdf.toFile();
			case 3 -> null;
			default -> throw new AssertionError("Unexpected file chooser invocation");
			};
		});

		// Simulate directory history established by earlier PDF work.
		assertEquals(previousPdf, picker.chooseAnyPdf(null, "Previous PDF"));

		// A new legacy import overrides that history with the
		// source spreadsheet directory.
		picker.preferInitialDirectory(workbookDirectory);

		// Cancelling must not discard the preferred directory.
		assertNull(picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		assertEquals(selectedPdf, picker.chooseAnyPdf(null, "Choose Question booklet PDF"));

		// Once a PDF is selected, its directory becomes authoritative.
		assertNull(picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		assertEquals(List.of(dataRoot, workbookDirectory, workbookDirectory, pdfDirectory), initialDirectories);
	}

	@Test
	void rejectsSiblingWhoseNameOnlySharesTheRootPrefix() {
		PdfFilePicker picker = new PdfFilePicker(dataRoot);
		Path sibling = dataRoot.resolveSibling(dataRoot.getFileName() + "-other").resolve("exam.pdf");
		assertFalse(picker.isInsideDataRoot(sibling));
	}

	@Test
	void selectedPdfProducesRelativeSourceDocumentPath() {
		Path pdf = dataRoot.resolve("chemistry/QCAA/2024/exam.pdf").toAbsolutePath().normalize();
		SelectedPdf selectedPdf = new SelectedPdf(pdf.toFile(), pdf, dataRoot.toAbsolutePath().normalize());
		assertEquals(Path.of("chemistry", "QCAA", "2024", "exam.pdf").toString(), selectedPdf.relativePath());
	}

	@Test
	void successiveBookletSelectionsUseLatestDirectory() throws Exception {
		Path firstDirectory = Files.createDirectory(dataRoot.resolve("first"));
		Path secondDirectory = Files.createDirectory(dataRoot.resolve("second"));
		Path questionOne = Files.createFile(firstDirectory.resolve("question-1.pdf"));
		Path answerOne = Files.createFile(firstDirectory.resolve("answer-1.pdf"));
		Path questionTwo = Files.createFile(secondDirectory.resolve("question-2.pdf"));
		Path answerTwo = Files.createFile(secondDirectory.resolve("answer-2.pdf"));
		List<File> selections = List.of(questionOne.toFile(), answerOne.toFile(), questionTwo.toFile(),
				answerTwo.toFile());
		AtomicInteger invocation = new AtomicInteger();
		List<Path> initialDirectories = new ArrayList<>();
		PdfFilePicker picker = new PdfFilePicker(dataRoot, (chooser, _) -> {
			initialDirectories.add(chooser.getInitialDirectory().toPath());

			// Remember the directory, not the previous filename.
			assertNull(chooser.getInitialFileName());
			return selections.get(invocation.getAndIncrement());
		});
		assertEquals(questionOne, picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		assertEquals(answerOne, picker.chooseAnyPdf(null, "Choose Answer booklet PDF"));
		assertEquals(questionTwo, picker.chooseAnyPdf(null, "Choose Question booklet PDF"));
		assertEquals(answerTwo, picker.chooseAnyPdf(null, "Choose Answer booklet PDF"));
		assertEquals(List.of(dataRoot, firstDirectory, firstDirectory, secondDirectory), initialDirectories);
	}
}
