package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@Tag("workflow-ui")
class CurriculumExamWorkflowTest extends QuestionBankApplicationUiTestBase {

	@Test
	void canSelectAndPersistHistoricalSyllabusClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		assertEquals(2, syllabuses.getItems().size());
		assertNotNull(syllabuses.getValue());
		assertEquals("2025", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("1", units.getItems().getFirst().getCode());
		assertTrue(subtopics.getItems().isEmpty());
		assertNotNull(descriptors.getValue());
		assertEquals("2025", model.getClassification().getSyllabusVersion().getName());
		SyllabusVersion historicalSelection = selectSyllabus(robot, "2019");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("2019", syllabuses.getValue().getName());
		assertEquals(1, units.getItems().size());
		assertEquals("3", units.getItems().getFirst().getCode());
		assertTrue(topics.getItems().isEmpty());
		assertTrue(subtopics.getItems().isEmpty());
		assertTrue(descriptors.getItems().isEmpty());
		assertFalse(units.isDisabled());
		assertTrue(topics.isDisabled());
		assertTrue(subtopics.isDisabled());
		assertTrue(descriptors.isDisabled());
		assertNull(units.getValue());
		assertNull(topics.getValue());
		assertNull(subtopics.getValue());
		assertNull(descriptors.getValue());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumNode historicalClassification = model.getClassification();
		assertNotNull(historicalClassification);
		assertEquals("3.1.1", historicalClassification.getCode());
		assertEquals(historicalSelection, historicalClassification.getSyllabusVersion());
		Question question = captureQuestion(robot, "H1");
		Question restored = new SqliteQuestionRepository(new SqliteDatabase(databasePath)).findById(question.getId())
				.orElseThrow();
		assertEquals(historicalClassification.getId(), restored.getClassification().getId());
		assertEquals(historicalSelection, restored.getClassification().getSyllabusVersion());
		assertFalse(restored.getClassification().getSyllabusVersion().isCurrent());
		assertEquals(2024, restored.getExam().getYear());
		assertEquals(historicalSelection, syllabuses.getValue());
		assertEquals(historicalSelection, model.getSyllabusVersion());
		assertNull(model.getClassification());
		assertFalse(units.isDisabled());
	}

	@Test
	void canSelectSubjectWithOnlyHistoricalSyllabus(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		selectSubject(robot, "Biology");
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals("Biology", subjects.getValue().getName());
		assertNull(examMetadataPane().getBooklet());
		assertEquals(1, syllabuses.getItems().size());
		assertEquals("2019", syllabuses.getItems().getFirst().getName());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
		assertTrue(units.getItems().isEmpty());
		assertTrue(units.isDisabled());
		SyllabusVersion historical = syllabuses.getItems().getFirst();
		robot.interact(() -> syllabuses.setValue(historical));
		WaitForAsyncUtils.waitForFxEvents();
		assertEquals(historical, syllabuses.getValue());
		assertEquals(1, units.getItems().size());
		assertEquals("2", units.getItems().getFirst().getCode());
		assertFalse(units.isDisabled());
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		assertEquals(historical, model.getClassification().getSyllabusVersion());
		assertEquals("2.1.1", model.getClassification().getCode());
	}

	@Test
	void capturesQuestionWithSubtopicClassification(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot, "Physics");
		Question savedQuestion = captureQuestion(robot, "P1");
		assertEquals("Physics", savedQuestion.getExam().getSubject().getName());
		assertEquals(CurriculumLevel.SUBTOPIC, savedQuestion.getClassification().getLevel());
		assertEquals(savedQuestion.getExam().getSubject(),
				savedQuestion.getClassification().getSyllabusVersion().getSubject());
	}

	@Test
	void changingSubjectInvalidatesPreviouslySetExamMetadata(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		assertNotNull(examMetadataPane().getBooklet());
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		selectSubject(robot, "Physics");
		WaitForAsyncUtils.waitForFxEvents();
		assertNull(examMetadataPane().getBooklet());
		assertEquals("Physics", subjects.getValue().getName());
	}

	@Test
	void clearingSyllabusThenSubjectClearsDependentControls(FxRobot robot) throws Exception {
		selectSubject(robot, "Chemistry");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<CurriculumNode> topics = comboBox(robot, "#curriculum-topic");
		ComboBox<CurriculumNode> subtopics = comboBox(robot, "#curriculum-subtopic");
		ComboBox<CurriculumNode> descriptors = comboBox(robot, "#curriculum-descriptor");
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		robot.interact(() -> syllabuses.getSelectionModel().clearSelection());
		assertEquals("Chemistry", subjects.getValue().getName());
		assertEquals(subjects.getValue(), model.getSubject());
		assertNull(syllabuses.getValue());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertFalse(syllabuses.isDisabled());
		assertEquals(2, syllabuses.getItems().size());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
		selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		robot.interact(() -> subjects.getSelectionModel().clearSelection());
		assertNull(model.getSubject());
		assertNull(model.getSyllabusVersion());
		assertNull(model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
		assertNull(syllabuses.getValue());
		assertTrue(syllabuses.getItems().isEmpty());
		assertTrue(syllabuses.isDisabled());
		for (ComboBox<CurriculumNode> box : List.of(units, topics, subtopics, descriptors)) {
			assertNull(box.getValue());
			assertTrue(box.getItems().isEmpty());
			assertTrue(box.isDisabled());
		}
	}

	@Test
	void examMetadataCanBeCorrectedFromSearch(FxRobot robot) throws Exception {

		// Reproduce the legacy-style malformed Exam metadata through the real import
		// workflow so both persistence and the active ExamMetadataPane see it.
		prepareExamAndClassification(robot, "Chemistry", "2022 QCAA", 2022, "2022", "Paper 1");
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		assertNotNull(originalBooklet);
		long originalExamId = originalBooklet.getExam().getId();
		long originalBookletId = originalBooklet.getId();
		long originalSourceDocumentId = originalBooklet.getSourceDocument().getId();
		Question question = captureQuestion(robot, "65");

		// Open Search Questions through the production application workflow.
		Platform.runLater(() -> {
			try {
				invoke(application, "showQuestionSearch", new Class<?>[] { Stage.class, ApplicationConfig.class },
						primaryStage, applicationConfig);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-subject").tryQuery().isPresent());
		ComboBox<Subject> subjectBox = comboBox(robot, "#question-search-subject");

		// Search populates its curriculum controls asynchronously. Wait for the
		// required Subject before retrieving it from the ComboBox.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> subjectBox.getItems().stream().anyMatch(subject -> "Chemistry".equals(subject.getName())));
		Subject chemistry = subjectBox.getItems().stream().filter(subject -> "Chemistry".equals(subject.getName()))
				.findFirst().orElseThrow();
		robot.interact(() -> subjectBox.setValue(chemistry));
		ListView<QuestionRetrievalResult> results = listView(robot, "#question-search-results");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> results.getItems().stream().anyMatch(result -> result.getQuestion().getId() == question.getId()));
		QuestionRetrievalResult selectedResult = results.getItems().stream()
				.filter(result -> result.getQuestion().getId() == question.getId()).findFirst().orElseThrow();
		robot.interact(() -> results.getSelectionModel().select(selectedResult));
		Button editExam = lookup(robot, "#question-search-edit-exam", Button.class);
		assertFalse(editExam.isDisabled());
		robot.clickOn(editExam);
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#exam-correction-provider").tryQuery().isPresent());
		TextField provider = lookup(robot, "#exam-correction-provider", TextField.class);
		TextField year = lookup(robot, "#exam-correction-year", TextField.class);
		TextField assessment = lookup(robot, "#exam-correction-assessment", TextField.class);

		// The correction dialog must show the malformed stored values rather than
		// inferred or default metadata.
		assertEquals("2022 QCAA", provider.getText());
		assertEquals("2022", year.getText());
		assertEquals("2022", assessment.getText());
		robot.interact(() -> {
			provider.setText("QCAA");
			year.setText("2022");
			assessment.setText("External Assessment");
		});
		robot.clickOn("#exam-correction-save");

		// Saving the correction should return to Search Questions.
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
				() -> robot.lookup("#question-search-results").tryQuery().isPresent());
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteQuestionRepository repository = new SqliteQuestionRepository(database);
		Question reloaded = repository.findById(question.getId()).orElseThrow();

		// The owning Exam is corrected in place, so downstream identities are
		// unchanged after a complete repository reload.
		assertEquals(originalExamId, reloaded.getExam().getId());
		assertEquals(originalBookletId, reloaded.getBooklet().getId());
		assertEquals(originalSourceDocumentId, reloaded.getBooklet().getSourceDocument().getId());
		assertEquals("QCAA", reloaded.getExam().getProvider().getName());
		assertEquals(2022, reloaded.getExam().getYear());
		assertEquals("External Assessment", reloaded.getExam().getName());

		// The currently active capture booklet must also be refreshed rather than
		// retaining the stale Exam object that existed before correction.
		ExamBooklet activeBooklet = examMetadataPane().getBooklet();
		assertNotNull(activeBooklet);
		assertEquals(originalBookletId, activeBooklet.getId());
		assertEquals(originalExamId, activeBooklet.getExam().getId());
		assertEquals("QCAA", activeBooklet.getExam().getProvider().getName());
		assertEquals("External Assessment", activeBooklet.getExam().getName());

		// The malformed provider is now orphaned and must disappear from both
		// authoritative persistence and future Import Exam suggestions.
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		assertFalse(examWriter.examProviderExists("2022 QCAA"));

		// ExamMetadataPane lives inside the Import Exam dialog, which is not currently
		// visible while Search Questions is open. Inspect its refreshed provider
		// control directly rather than querying the active TestFX scene graph.
		ComboBox<?> importProviders = field(examMetadataPane(), "providerField", ComboBox.class);
		assertFalse(importProviders.getItems().stream().map(Object::toString).anyMatch("2022 QCAA"::equalsIgnoreCase));
		assertTrue(importProviders.getItems().stream().map(Object::toString).anyMatch("QCAA"::equalsIgnoreCase));

		// Search refresh must still contain the same Question after its owning Exam
		// metadata changes.
		ListView<QuestionRetrievalResult> refreshedResults = listView(robot, "#question-search-results");
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> refreshedResults.getItems().stream()
				.anyMatch(result -> result.getQuestion().getId() == question.getId()));
		robot.clickOn("Close");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	void externalIdenticalExamPdfIsRejectedWhenPersistedMatchIsAmbiguous(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet original = examMetadataPane().getBooklet();
		assertNotNull(original);
		PdfStore pdfStore = new PdfStore(pdfDataRoot);
		Path firstStoredPdf = pdfStore.resolve(original.getSourceDocument().getRelativePath());
		assertTrue(Files.isRegularFile(firstStoredPdf));
		SqliteDatabase database = new SqliteDatabase(databasePath);
		SqliteExamWriter writer = new SqliteExamWriter(database);

		// Persist a second booklet backed by a different managed source file whose
		// bytes are deliberately identical to the first booklet's PDF.
		Path secondStoredPdf = firstStoredPdf.getParent().resolve("byte-identical-second.pdf");
		Files.copy(firstStoredPdf, secondStoredPdf);
		String secondRelativePath = pdfDataRoot.relativize(secondStoredPdf).toString();
		var secondSource = writer.insertSourceDocument(secondRelativePath);
		var secondBooklet = writer.insertExamBooklet(original.getExam(), secondSource, "Paper 2");
		assertTrue(secondBooklet.getId() != original.getId());
		assertEquals(2, writer.findAllExamBooklets().size());

		// This third file is outside the managed store and has a completely
		// unrelated filename. It is byte-identical to both persisted sources.
		Path externalCopy = databasePath.getParent().resolve("ambiguous-external-copy.pdf");
		Files.copy(firstStoredPdf, externalCopy);
		assertFalse(externalCopy.startsWith(pdfDataRoot));
		WaitForAsyncUtils.asyncFx(() -> {
			examMetadataPane().beginImport();
			examImportDialog().show();
		}).get();

		// stageExamPdf shows a modal error for ambiguity, so start it asynchronously
		// rather than waiting for the call itself to return.
		Platform.runLater(() -> examMetadataPane().stageExamPdf(externalCopy));
		WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot
				.lookup("Selected PDF matches more than one persisted exam booklet.").tryQuery().isPresent());

		// Ambiguity must be reported rather than resolved by filename, insertion
		// order or any other arbitrary choice.
		assertTrue(robot.lookup("Exam details could not be saved.").tryQuery().isPresent());
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot.lookup("OK").tryQuery().isPresent());
		Button okButton = robot.lookup("OK").queryButton();
		robot.interact(okButton::fire);
		WaitForAsyncUtils.waitForFxEvents();

		// Rejection must not create another ExamBooklet or alter either existing
		// persisted relationship.
		List<ExamBooklet> persisted = writer.findAllExamBooklets();
		assertEquals(2, persisted.size());
		assertTrue(persisted.stream().anyMatch(booklet -> booklet.getId() == original.getId()));
		assertTrue(persisted.stream().anyMatch(booklet -> booklet.getId() == secondBooklet.getId()));

		// The failed selection must not remain staged for confirmation.
		Path pendingPdfPath = field(examMetadataPane(), "pendingPdfPath", Path.class);
		ExamBooklet pendingKnownBooklet = field(examMetadataPane(), "pendingKnownBooklet", ExamBooklet.class);
		assertNull(pendingPdfPath);
		assertNull(pendingKnownBooklet);
		robot.clickOn("#cancel-exam-import");
		WaitForAsyncUtils.waitForFxEvents();
	}

	@Test
	void externalIdenticalExamPdfReusesPersistedBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet original = examMetadataPane().getBooklet();
		assertNotNull(original);
		Path storedPdf = new PdfStore(pdfDataRoot).resolve(original.getSourceDocument().getRelativePath());
		assertTrue(Files.isRegularFile(storedPdf));

		// Use a different filename outside the managed PDF root. Recognition must
		// therefore come from byte identity rather than filename or directory names.
		Path externalCopy = databasePath.getParent().resolve("completely-different-name.pdf");
		Files.copy(storedPdf, externalCopy);
		assertFalse(externalCopy.startsWith(pdfDataRoot));
		WaitForAsyncUtils.asyncFx(() -> {
			examMetadataPane().beginImport();
			examImportDialog().show();
		}).get();
		WaitForAsyncUtils.asyncFx(() -> examMetadataPane().stageExamPdf(externalCopy)).get();
		ComboBox<Subject> subject = comboBox(robot, "#exam-subject");
		ComboBox<String> provider = comboBox(robot, "#exam-provider");
		ComboBox<Integer> year = comboBox(robot, "#exam-year");
		ComboBox<String> assessment = comboBox(robot, "#exam-assessment");
		ComboBox<String> booklet = comboBox(robot, "#exam-booklet");

		// The differently named external copy must resolve to the metadata already
		// owned by the byte-identical persisted source document.
		assertEquals(original.getExam().getSubject().getId(), subject.getValue().getId());
		assertEquals(original.getExam().getProvider().getName(), provider.getValue());
		assertEquals(original.getExam().getYear(), year.getValue());
		assertEquals(original.getExam().getName(), assessment.getValue());
		assertEquals(original.getName(), booklet.getValue());
		assertTrue(subject.isDisabled());
		assertTrue(provider.isDisabled());
		assertTrue(year.isDisabled());
		assertTrue(assessment.isDisabled());
		assertTrue(booklet.isDisabled());
		robot.clickOn("#confirm-exam-details");
		WaitForAsyncUtils.waitForFxEvents();
		ExamBooklet reopened = examMetadataPane().getBooklet();
		assertNotNull(reopened);

		// Reopening an external identical copy must activate the original persisted
		// booklet rather than create a duplicate Exam, SourceDocument or Booklet.
		assertEquals(original.getExam().getId(), reopened.getExam().getId());
		assertEquals(original.getId(), reopened.getId());
		assertEquals(original.getSourceDocument().getId(), reopened.getSourceDocument().getId());
		assertEquals(original.getSourceDocument().getRelativePath(), reopened.getSourceDocument().getRelativePath());
		SqliteExamWriter writer = new SqliteExamWriter(new SqliteDatabase(databasePath));
		assertEquals(1, writer.findAllExamBooklets().size());

		// Confirmation opens the authoritative managed source rather than changing
		// persistence to point at the external copy.
		assertEquals(PdfWorkspacePane.DocumentMode.EXAM, pdfWorkspace().getDisplayedDocument());
	}

	@Test
	void importedExamPdfUsesSubjectProviderYearHierarchy(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		Path expectedPath = pdfDataRoot.resolve("Chemistry").resolve("QCAA").resolve("2024")
				.resolve(examPdf.getFileName());
		assertTrue(Files.isRegularFile(expectedPath));
		ExamBooklet booklet = examMetadataPane().getBooklet();
		assertNotNull(booklet);
		assertEquals(pdfDataRoot.relativize(expectedPath).toString(), booklet.getSourceDocument().getRelativePath());
	}

	@Test
	void knownManagedExamPdfReusesStoredMetadataAndBooklet(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet original = examMetadataPane().getBooklet();
		assertNotNull(original);
		long originalExamId = original.getExam().getId();
		long originalBookletId = original.getId();
		long originalSourceDocumentId = original.getSourceDocument().getId();
		Path storedPdf = new PdfStore(pdfDataRoot).resolve(original.getSourceDocument().getRelativePath());
		assertTrue(Files.isRegularFile(storedPdf));

		// Start another Open Exam workflow and select the PDF that persistence
		// already identifies as this booklet.
		WaitForAsyncUtils.asyncFx(() -> {
			examMetadataPane().beginImport();
			examImportDialog().show();
		}).get();
		WaitForAsyncUtils.asyncFx(() -> examMetadataPane().stageExamPdf(storedPdf)).get();
		ComboBox<Subject> subject = comboBox(robot, "#exam-subject");
		ComboBox<String> provider = comboBox(robot, "#exam-provider");
		ComboBox<Integer> year = comboBox(robot, "#exam-year");
		ComboBox<String> assessment = comboBox(robot, "#exam-assessment");
		ComboBox<String> booklet = comboBox(robot, "#exam-booklet");

		// Recognition must populate the persisted metadata without requiring the
		// user to re-enter or infer anything from the filename.
		assertEquals(original.getExam().getSubject().getId(), subject.getValue().getId());
		assertEquals(original.getExam().getProvider().getName(), provider.getValue());
		assertEquals(original.getExam().getYear(), year.getValue());
		assertEquals(original.getExam().getName(), assessment.getValue());
		assertEquals(original.getName(), booklet.getValue());

		// Existing metadata is authoritative in this workflow. Corrections belong
		// to Edit Exam rather than creating a second hierarchy for the same source.
		assertTrue(subject.isDisabled());
		assertTrue(provider.isDisabled());
		assertTrue(year.isDisabled());
		assertTrue(assessment.isDisabled());
		assertTrue(booklet.isDisabled());
		robot.clickOn("#confirm-exam-details");
		WaitForAsyncUtils.waitForFxEvents();
		ExamBooklet reopened = examMetadataPane().getBooklet();
		assertNotNull(reopened);

		// Opening the known PDF must retain every persistent identity.
		assertEquals(originalExamId, reopened.getExam().getId());
		assertEquals(originalBookletId, reopened.getId());
		assertEquals(originalSourceDocumentId, reopened.getSourceDocument().getId());
		assertEquals(original.getExam().getProvider().getName(), reopened.getExam().getProvider().getName());
		assertEquals(original.getExam().getYear(), reopened.getExam().getYear());
		assertEquals(original.getExam().getName(), reopened.getExam().getName());
		assertEquals(original.getName(), reopened.getName());
	}

	@Test
	void refreshingSubjectsReloadsVersionsWithoutReplacingHistoricalSelection(FxRobot robot) throws Exception {
		prepareExamAndClassification(robot);
		ExamBooklet originalBooklet = examMetadataPane().getBooklet();
		SyllabusVersion historical = selectSyllabus(robot, "2019");
		selectFirst(robot, "#curriculum-unit");
		selectFirst(robot, "#curriculum-topic");
		selectFirstFinalClassification(robot);
		CurriculumSelectionModel model = field(application, "curriculumSelectionModel", CurriculumSelectionModel.class);
		CurriculumNode classification = model.getClassification();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(new SqliteDatabase(databasePath));
		SyllabusVersion imported = writer.insertSyllabusVersion(historical.getSubject(), "2015", false);
		writer.insertSubject("Astronomy");
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		robot.interact(pane::refreshSubjects);
		assertEquals(3, syllabuses.getItems().size());
		assertTrue(syllabuses.getItems().contains(imported));
		assertEquals(historical, syllabuses.getValue());
		assertEquals(historical, model.getSyllabusVersion());
		assertEquals(historical.getSubject(), model.getSubject());
		assertEquals(classification, model.getClassification());
		assertClassificationControlShows(robot, classification);
		assertEquals("3", units.getItems().getFirst().getCode());
		assertEquals(originalBooklet, examMetadataPane().getBooklet());
		assertEquals(4, comboBox(robot, "#curriculum-subject").getItems().size());
	}

	@Test
	void resettingClassificationWithoutSyllabusKeepsUnitsDisabled(FxRobot robot) throws Exception {
		CurriculumSelectorPane pane = field(application, "curriculumSelectorPane", CurriculumSelectorPane.class);
		ComboBox<CurriculumNode> units = comboBox(robot, "#curriculum-unit");
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		selectSubject(robot, "Biology");
		robot.interact(pane::clearClassificationBelowSubject);
		assertTrue(units.isDisabled());
		assertNull(syllabuses.getValue());
		assertFalse(syllabuses.isDisabled());
	}

	@Override
	@Start
	void start(Stage stage) throws Exception {
		super.start(stage);
	}

	private void selectSubject(FxRobot robot, String subjectName) {
		ComboBox<Subject> subjects = comboBox(robot, "#curriculum-subject");
		Subject selectedSubject = null;
		for (Subject subject : subjects.getItems()) {
			if (subjectName.equals(subject.getName())) {
				selectedSubject = subject;
				break;
			}
		}
		assertNotNull(selectedSubject);
		Subject subjectSelection = selectedSubject;
		robot.interact(() -> subjects.setValue(subjectSelection));
	}

	private SyllabusVersion selectSyllabus(FxRobot robot, String name) {
		ComboBox<SyllabusVersion> syllabuses = comboBox(robot, "#curriculum-syllabus");
		for (SyllabusVersion version : syllabuses.getItems()) {
			if (name.equals(version.getName())) {
				robot.interact(() -> syllabuses.setValue(version));
				return version;
			}
		}
		throw new AssertionError("Syllabus not found: " + name);
	}
}
