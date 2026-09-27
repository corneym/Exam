package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.render.QuestionContentRenderer;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class LegacyClipboardCaptureWorkflowIntegrationTest extends QuestionBankApplicationUiTestBase {

	@Test
	void importedLegacyQuestionCanBeCompletedWithClipboardAndPdfContent(FxRobot robot) throws Exception {

		// Create the real persisted Exam/booklet that the legacy workbook will
		// reference. The importer deliberately does not invent missing booklets.
		prepareExamAndClassification(robot, "Chemistry", "QCAA", 2024, "External Assessment", "MCQ booklet");
		SqliteDatabase database = new SqliteDatabase(databasePath);

		// Give the historical syllabus a Subtopic with a finer Descriptor so the
		// capture workflow can exercise the same classification refinement used
		// during the live test.
		Descriptor targetDescriptor = createHistoricalCaptureDescriptor(database);
		String importedSubtopicCode = targetDescriptor.getParent().getCode();
		Path workbookPath = createLegacyCaptureWorkbook(importedSubtopicCode);
		LegacyQuestionImportResult importResult = new LegacyQuestionMetadataImporter(database)
				.importWorkbook(workbookPath, "Chemistry", "2019");
		assertEquals(new LegacyQuestionImportResult(1, 0, 1), importResult);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question importedQuestion = repository.findAll().stream()
				.filter(question -> "41".equals(question.getQuestionCode())).findFirst().orElseThrow();
		assertTrue(importedQuestion.getContentParts().isEmpty());
		assertEquals(importedSubtopicCode, importedQuestion.getClassification().getCode());
		assertEquals(QuestionResponseType.MULTIPLE_CHOICE, importedQuestion.getResponseType());
		@SuppressWarnings("unchecked")
		ComboBox<Subject> workingSubjectBox = lookup(robot, "#curriculum-subject", ComboBox.class);

		// Clear the active Subject before entering imported capture. This invalidates
		// the active booklet and forces mouse selection of the imported Question to
		// reactivate its booklet and Subject, reproducing the transition that caused
		// the JavaFX ListView failure.
		robot.interact(() -> workingSubjectBox.setValue(null));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(null, examMetadataPane().getBooklet());

		// Enter the production imported-Question workflow through its real control.
		fireControl(robot, "#capture-mode-imported");
		ComboBox<Question> importedQuestionBox = comboBox(robot, "#imported-question");
		assertEquals(1, importedQuestionBox.getItems().size());

		// Pointer behaviour matters here: the defect occurred while the ComboBox
		// popup ListView was processing this mouse selection.
		robot.clickOn(importedQuestionBox);
		WaitForAsyncUtils.waitForFxEvents();
		Node importedQuestionCell = robot.lookup(".list-cell").match(node -> {
			if (!(node instanceof ListCell<?> cell) || !node.isVisible()) {
				return false;
			}
			Object item = cell.getItem();
			return item instanceof Question question && question.getId() == importedQuestion.getId();
		}).query();
		robot.clickOn(importedQuestionCell);
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(importedQuestion.getId(), importedQuestionBox.getValue().getId());
		assertEquals("Chemistry", workingSubjectBox.getValue().getName());
		assertEquals(importedQuestion.getBooklet().getId(), examMetadataPane().getBooklet().getId());

		// Put a deterministic image onto the real JavaFX clipboard. Production
		// clipboard capture will convert this image to the persisted PNG form.
		robot.interact(() -> {
			WritableImage image = new WritableImage(40, 20);
			for (int y = 0; y < 20; y++) {
				for (int x = 0; x < 40; x++) {
					image.getPixelWriter().setColor(x, y, Color.ORANGE);
				}
			}
			ClipboardContent clipboardContent = new ClipboardContent();
			clipboardContent.putImage(image);
			Clipboard.getSystemClipboard().setContent(clipboardContent);
		});
		fireControl(robot, "#paste-question-image");
		assertEquals("Content parts: 1", lookup(robot, "#question-region-count", Label.class).getText());

		// Navigate away from the initially displayed PDF page before taking the
		// second part, proving that the persisted PDF region retains its source page.
		fireControl(robot, "#next-pdf-page");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("2", lookup(robot, "#pdf-page-number", TextField.class).getText());
		dragRegionOnDisplayedPage(robot);
		fireControl(robot, "#add-question-region");
		assertEquals("Content parts: 2", lookup(robot, "#question-region-count", Label.class).getText());
		@SuppressWarnings("unchecked")
		ComboBox<CurriculumNode> descriptorBox = lookup(robot, "#curriculum-descriptor", ComboBox.class);
		CurriculumNode availableDescriptor = descriptorBox.getItems().stream()
				.filter(node -> node.getId() == targetDescriptor.getId()).findFirst().orElseThrow();

		// Classification selection itself is not a pointer-behaviour test, so use the
		// JavaFX selection model directly for deterministic desktop/headless results.
		robot.interact(() -> descriptorBox.getSelectionModel().select(availableDescriptor));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(targetDescriptor.getId(), descriptorBox.getValue().getId());

		// Save through the production Question-capture control. This persists the
		// mixed body and refined Descriptor in one capture operation.
		fireControl(robot, "#save-question");
		WaitForAsyncUtils
				.waitFor(10, TimeUnit.SECONDS,
						() -> repository.findById(importedQuestion.getId())
								.map(question -> question.getContentParts().size() == 2
										&& question.getClassification().getId() == targetDescriptor.getId())
								.orElse(false));
		WaitForAsyncUtils.waitForFxEvents();
		Question reloaded = repository.findById(importedQuestion.getId()).orElseThrow();

		// The authoritative assembly must match the actual capture sequence:
		// pasted image first, followed by the PDF region.
		assertEquals(2, reloaded.getContentParts().size());
		assertTrue(reloaded.getContentParts().get(0) instanceof ImageQuestionContentPart);
		assertTrue(reloaded.getContentParts().get(1) instanceof PdfQuestionContentPart);
		assertEquals(1, reloaded.getRegions().size());
		assertEquals(2, reloaded.getRegions().getFirst().pageNumber());
		assertEquals(targetDescriptor.getId(), reloaded.getClassification().getId());

		// Once body capture is complete the legacy Question must leave the imported
		// capture queue rather than remaining as apparently unresolved work.
		assertTrue(importedQuestionBox.getItems().isEmpty());
		QuestionContentRenderer renderer = new QuestionContentRenderer(new PdfStore(pdfDataRoot),
				new QuestionExtractor());
		BufferedImage rendered = renderer.renderQuestionBody(reloaded);
		assertTrue(rendered.getWidth() > 0);
		assertTrue(rendered.getHeight() > 20);

		// The orange clipboard image was captured first. Its top-left RGB value at
		// the top of the assembled render proves that mixed rendering retained the
		// authoritative IMAGE -> PDF_REGION order after database reload.
		assertEquals(0x00FFA500, rendered.getRGB(0, 0) & 0x00FFFFFF);
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private Descriptor createHistoricalCaptureDescriptor(SqliteDatabase database) throws Exception {
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Subject chemistry = repository.findAllSubjects().stream()
				.filter(subject -> "Chemistry".equals(subject.getName())).findFirst().orElseThrow();
		SyllabusVersion historicalSyllabus = repository.findVersionsForSubject(chemistry).stream()
				.filter(version -> "2019".equals(version.getName())).findFirst().orElseThrow();
		CurriculumNode historicalTopicNode = repository.findByCode(historicalSyllabus, "3.1").orElseThrow();
		if (!(historicalTopicNode instanceof Topic historicalTopic)) {
			throw new AssertionError("Historical 3.1 node is not a Topic");
		}
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);

		// The workbook imports the broader Subtopic. The UI subsequently refines
		// that original classification to the Descriptor beneath it.
		Subtopic importedSubtopic = writer.insertSubtopic(historicalTopic, "3.1.2", "Imported capture subtopic", 2);
		return writer.insertDescriptor(importedSubtopic, "3.1.2.1", "Imported capture descriptor", 1);
	}

	private Path createLegacyCaptureWorkbook(String classificationCode) throws Exception {
		Path workbookPath = databasePath.getParent().resolve("legacy-clipboard-capture.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Year");
			header.createCell(1).setCellValue("Paper");
			header.createCell(2).setCellValue("Question");
			header.createCell(3).setCellValue("Marks");
			header.createCell(4).setCellValue("Topic");
			header.createCell(5).setCellValue("Answer");
			header.createCell(6).setCellValue("Preamble");
			Row question = sheet.createRow(1);

			// Match the already persisted QCAA 2024 MCQ booklet while leaving the
			// Question body absent for later clipboard/PDF capture.
			question.createCell(0).setCellValue(2024);
			question.createCell(1).setCellValue("MCQ");
			question.createCell(2).setCellValue("41");
			question.createCell(3).setCellValue(1);
			question.createCell(4).setCellValue(classificationCode);
			question.createCell(5).setCellValue("B");
			try (OutputStream output = Files.newOutputStream(workbookPath)) {

				// Writing a genuine XLSX exercises the production Apache POI reader
				// rather than bypassing workbook parsing with constructed row objects.
				workbook.write(output);
			}
		}
		return workbookPath;
	}
}
