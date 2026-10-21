package au.edu.eq.questionbank.ui.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.diagnostics.PerformanceRecorder;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.service.curriculum.DescriptorLookupService.DescriptorMatch;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
public class CurriculumSelectorPaneTest {

	// Test environment and collaborators.
	private CurriculumSelectionModel model;
	private CurriculumSelectorPane pane;

	// Curriculum and assessment fixtures.
	private Subject chemistry;
	private Descriptor descriptor3111;
	private Descriptor descriptor321;
	private Subtopic subtopic311;
	private SyllabusVersion syllabus2019;
	private SyllabusVersion syllabus2025;
	private Topic topic31;
	private Topic topic32;
	private Unit unit3;

	@Test
	public void changingSyllabusClearsCodeAndClassificationButRetainsSubject(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		@SuppressWarnings("unchecked")
		ComboBox<SyllabusVersion> syllabuses = robot.lookup("#curriculum-syllabus").queryAs(ComboBox.class);
		robot.clickOn(codeField).write("3.1.1.1");
		robot.interact(() -> syllabuses.setValue(syllabus2019));
		assertEquals(chemistry, model.getSubject());
		assertEquals(syllabus2019, model.getSyllabusVersion());
		assertEquals("", codeField.getText());
		assertNull(model.getUnit());
		assertNull(model.getClassification());
		assertFalse(pane.classificationSelectedProperty().get());
	}

	@Test
	public void clearingDescriptorKeywordsRestoresHierarchyControls(FxRobot robot) throws Exception {
		robot.interact(() -> pane.selectClassificationPath(descriptor3111));
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		Label status = robot.lookup("#curriculum-descriptor-search-status").queryAs(Label.class);
		ComboBox<?> syllabuses = robot.lookup("#curriculum-syllabus").queryComboBox();
		ComboBox<?> units = robot.lookup("#curriculum-unit").queryComboBox();
		ComboBox<?> topics = robot.lookup("#curriculum-topic").queryComboBox();
		ComboBox<?> subtopics = robot.lookup("#curriculum-subtopic").queryComboBox();
		ComboBox<?> descriptors = robot.lookup("#curriculum-descriptor").queryComboBox();

		// Exercise the locked-syllabus workflow used by
		// existing Question editing.
		robot.interact(() -> pane.setSyllabusContextLocked(true));
		robot.interact(() -> search.setText("nonexistent keyword"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No matching Descriptors.".equals(status.getText()));
		robot.interact(search::clear);
		assertEquals(descriptor3111, model.getClassification());
		assertFalse(units.isDisabled());
		assertFalse(topics.isDisabled());
		assertFalse(subtopics.isDisabled());
		assertFalse(descriptors.isDisabled());

		// Clearing lookup text must not override a deliberate
		// lock on the authoritative syllabus.
		assertTrue(syllabuses.isDisabled());
		assertFalse(status.isVisible());
	}

	@Test
	public void codeEntryRejectsImpossibleCodeExtension(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.clickOn(codeField).write("3.1.1");
		assertEquals("3.1.1", codeField.getText());
		assertEquals(subtopic311, model.getSubtopic());
		robot.write("0");
		assertEquals("3.1.1", codeField.getText());
		assertEquals(subtopic311, model.getSubtopic());
	}

	@Test
	public void codeEntryRejectsNonNumericCharacters(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.clickOn(codeField).write("3a");
		assertEquals("3", codeField.getText());
		assertEquals(unit3, model.getUnit());
	}

	@Test
	public void codeEntryRejectsRepeatedPeriods(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.clickOn(codeField).write("3.");
		assertEquals("3.", codeField.getText());
		robot.write(".");
		assertEquals("3.", codeField.getText());
	}

	@Test
	public void codeEntrySelectsHierarchyProgressively(FxRobot robot) {
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);

		// Each progressively completed code level must update both the model and the
		// visible hierarchy control for that level.
		robot.clickOn("#curriculum-code").write("3");
		assertEquals(unit3, units.getValue());
		assertEquals(unit3, model.getUnit());
		assertNull(topics.getValue());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		robot.write(".1");
		assertEquals(topic31, topics.getValue());
		assertEquals(topic31, model.getTopic());
		assertNull(subtopics.getValue());
		assertNull(model.getClassification());
		robot.write(".1");
		assertEquals(subtopic311, subtopics.getValue());
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(subtopic311, model.getClassification());
		robot.write(".1");
		assertEquals(descriptor3111, descriptors.getValue());
		assertEquals(descriptor3111, model.getDescriptor());
		assertEquals(descriptor3111, model.getClassification());
	}

	@Test
	public void completeCodeDisplaysCompleteHierarchyPath(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);

		// Entering one complete classification code must synchronise every visible
		// hierarchy control, not merely resolve the final classification in the model.
		robot.interact(() -> codeField.setText("3.1.1.1"));
		assertEquals(unit3, units.getValue());
		assertEquals(topic31, topics.getValue());
		assertEquals(subtopic311, subtopics.getValue());
		assertEquals(descriptor3111, descriptors.getValue());

		// The backing model must describe the same path shown by the controls.
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(descriptor3111, model.getDescriptor());
	}

	@Test
	public void completeCodePasteSelectsDescriptor(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.interact(() -> codeField.setText("3.1.1.1"));
		assertEquals(descriptor3111, model.getDescriptor());
		assertEquals(descriptor3111, pane.selectedClassificationProperty().get());
		assertTrue(pane.classificationSelectedProperty().get());
	}

	@Test
	public void descriptorLookupFollowsClassificationDisable(FxRobot robot) {
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		robot.interact(() -> pane.setClassificationControlsDisabled(true));
		assertTrue(search.isDisabled());
		robot.interact(() -> pane.setClassificationControlsDisabled(false));
		assertFalse(search.isDisabled());
	}

	@Test
	public void descriptorLookupNarrowsWithCodeAndTopic(FxRobot robot) throws Exception {
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		TextField code = robot.lookup("#curriculum-code").queryAs(TextField.class);
		ListView<DescriptorMatch> results = robot.lookup("#curriculum-descriptor-search-results").queryListView();
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryComboBox();
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryComboBox();
		robot.interact(() -> search.setText("descriptor"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);

		// A partial code restricts search to that Topic.
		robot.interact(() -> code.setText("3.1"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().descriptor().equals(descriptor3111));

		// Changing only the Code must refresh active keywords.
		robot.interact(() -> code.setText("3.2"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().descriptor().equals(descriptor321));

		// With no Code or Unit/Topic selection, the entire
		// selected syllabus is available again.
		robot.interact(code::clear);
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 2);

		// Selecting Unit and Topic through the existing
		// hierarchy has the same narrowing effect.
		robot.interact(() -> units.getSelectionModel().select(unit3));
		robot.interact(() -> topics.getSelectionModel().select(topic31));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().descriptor().equals(descriptor3111));

		// Searching must not commit a classification.
		assertNull(model.getClassification());
	}

	@Test
	public void descriptorLookupPopulatesTopicAndReplacesPreviousHierarchy(FxRobot robot) throws Exception {

		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);

		TextField code = robot.lookup("#curriculum-code").queryAs(TextField.class);

		ListView<DescriptorMatch> results = robot.lookup("#curriculum-descriptor-search-results").queryListView();

		@SuppressWarnings("unchecked")
		ComboBox<SyllabusVersion> syllabuses = robot.lookup("#curriculum-syllabus").queryAs(ComboBox.class);

		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);

		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);

		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);

		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);

		// First select a descriptor beneath a Subtopic using lookup.
		robot.interact(() -> search.setText("3.1.1.1"));

		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().descriptor().equals(descriptor3111));

		robot.interact(() -> {
			results.getSelectionModel().selectFirst();
			results.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
		});

		// Verify actual visible ComboBox values, not only model state.
		assertEquals(syllabus2025, syllabuses.getValue());
		assertEquals(unit3, units.getValue());
		assertEquals(topic31, topics.getValue());
		assertEquals(subtopic311, subtopics.getValue());
		assertEquals(descriptor3111, descriptors.getValue());

		assertEquals(topic31, topics.getSelectionModel().getSelectedItem());
		assertTrue(topics.getItems().contains(topic31));

		assertEquals("3.1.1.1", code.getText());
		assertEquals("", search.getText());

		// Verify the hierarchy is internally consistent.
		assertEquals(unit3, topic31.getParent());
		assertEquals(topic31, subtopic311.getParent());
		assertEquals(subtopic311, descriptor3111.getParent());

		assertEquals(topic31, model.getTopic());
		assertEquals(descriptor3111, model.getClassification());
		assertEquals(descriptor3111, pane.selectedClassificationProperty().get());

		// Broaden the Code scope, then locate a different Descriptor.
		// This one belongs directly to Topic 3.2.
		robot.interact(() -> code.setText("3"));
		robot.interact(() -> search.setText("3.2.1"));

		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1
				&& results.getItems().getFirst().descriptor().equals(descriptor321));

		robot.interact(() -> {
			results.getSelectionModel().selectFirst();
			results.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
		});

		// The second result must replace the previous hierarchy,
		// including the actual displayed Topic selection.
		assertEquals(syllabus2025, syllabuses.getValue());
		assertEquals(unit3, units.getValue());
		assertEquals(topic32, topics.getValue());
		assertNull(subtopics.getValue());
		assertEquals(descriptor321, descriptors.getValue());

		assertEquals(topic32, topics.getSelectionModel().getSelectedItem());
		assertTrue(topics.getItems().contains(topic32));
		assertFalse(subtopics.isVisible());

		assertEquals("3.2.1", code.getText());
		assertEquals("", search.getText());

		assertEquals(unit3, topic32.getParent());
		assertEquals(topic32, descriptor321.getParent());
		assertEquals(syllabus2025, descriptor321.getSyllabusVersion());

		assertEquals(topic32, model.getTopic());
		assertNull(model.getSubtopic());
		assertEquals(descriptor321, model.getClassification());
		assertEquals(descriptor321, pane.selectedClassificationProperty().get());
	}

	@Test
	public void descriptorLookupRespectsSyllabusSwitch(FxRobot robot) throws Exception {
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		ComboBox<SyllabusVersion> syllabuses = robot.lookup("#curriculum-syllabus").queryComboBox();
		ListView<DescriptorMatch> results = robot.lookup("#curriculum-descriptor-search-results").queryListView();
		Label status = robot.lookup("#curriculum-descriptor-search-status").queryAs(Label.class);
		robot.interact(() -> search.setText("3.1.1.1"));

		// Change syllabus before the earlier asynchronous result
		// is necessarily delivered.
		robot.interact(() -> syllabuses.setValue(syllabus2019));
		assertEquals("", search.getText());
		assertTrue(results.getItems().isEmpty());
		assertEquals(syllabus2019, model.getSyllabusVersion());
		robot.interact(() -> search.setText("3.1.1.1"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No matching Descriptors.".equals(status.getText()));
		assertTrue(results.getItems().isEmpty());
		assertNull(model.getClassification());
	}

	@Test
	public void descriptorLookupSelectsExistingHierarchy(FxRobot robot) throws Exception {
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		ListView<DescriptorMatch> results = robot.lookup("#curriculum-descriptor-search-results").queryListView();
		TextField code = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.interact(() -> search.setText("3.1.1.1"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> results.getItems().size() == 1);
		assertNull(model.getClassification());
		assertEquals(descriptor3111, results.getItems().getFirst().descriptor());

		// Keyboard selection explicitly accepts the highlighted match.
		robot.interact(() -> {
			results.getSelectionModel().selectFirst();
			results.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
		});
		assertEquals(descriptor3111, model.getClassification());
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals("3.1.1.1", code.getText());
		assertEquals("", search.getText());
	}

	@Test
	public void descriptorLookupTermsDoNotChangeClassification(FxRobot robot) throws Exception {
		robot.interact(() -> pane.selectClassificationPath(descriptor3111));
		TextField search = robot.lookup("#curriculum-descriptor-search").queryAs(TextField.class);
		Label status = robot.lookup("#curriculum-descriptor-search-status").queryAs(Label.class);
		robot.interact(() -> search.setText("nonexistent keyword"));
		WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> "No matching Descriptors.".equals(status.getText()));
		assertEquals(descriptor3111, model.getClassification());
		robot.interact(search::clear);
		assertEquals(descriptor3111, model.getClassification());
		assertEquals("3.1.1.1", robot.lookup("#curriculum-code").queryAs(TextField.class).getText());
		assertFalse(status.isVisible());
	}

	@Test
	public void detachingSubjectContextPreservesLiveSelectorAndClassification(FxRobot robot) {
		@SuppressWarnings("unchecked")
		ComboBox<Subject> originalSubjectBox = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		AtomicReference<javafx.scene.layout.VBox> detachedContext = new AtomicReference<>();

		// Re-parenting JavaFX nodes must occur on the application thread.
		robot.interact(() -> detachedContext.set(pane.detachSubjectContext()));
		VBox subjectContext = detachedContext.get();
		assertEquals("working-subject-context", subjectContext.getId());
		assertEquals(originalSubjectBox, subjectContext.lookup("#curriculum-subject"));
		assertFalse(pane.getChildren().contains(subjectContext));

		// Classification remains owned by CurriculumSelectorPane for later placement in
		// the Capture workspace.
		assertTrue(pane.getChildren().stream().anyMatch(node -> "classification-context".equals(node.getId())));

		// The detached Subject control remains bound to the same selection model and
		// application-facing API after re-parenting.
		robot.interact(() -> pane.selectSubject(null));
		assertNull(originalSubjectBox.getValue());
		assertNull(model.getSubject());
		robot.interact(() -> pane.selectSubject(chemistry));
		assertEquals(chemistry, originalSubjectBox.getValue());
		assertEquals(chemistry, model.getSubject());
	}

	@Test
	public void directTopicDescriptorDoesNotShowSubtopicRow(FxRobot robot) {
		ComboBox<?> subtopicBox = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		ComboBox<?> descriptorBox = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		robot.clickOn("#curriculum-code").write("3.2");
		assertFalse(subtopicBox.isVisible());
		assertTrue(descriptorBox.isVisible());
		assertNull(model.getClassification());
		robot.write(".1");
		assertEquals(descriptor321, model.getDescriptor());
		assertEquals(descriptor321, model.getClassification());
	}

	@Test
	public void disabledHierarchyDiagnosticsCreateNoFile(FxRobot robot) throws Exception {
		Path csv = Files.createTempDirectory("curriculum-disabled-performance-").resolve("performance.csv");
		robot.interact(() -> {
			pane.setPerformanceRecorder(new PerformanceRecorder(false, csv));
		});
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		robot.interact(() -> {
			units.getSelectionModel().select(unit3);
			topics.getSelectionModel().select(topic31);
		});
		assertEquals(topic31, model.getTopic());
		assertFalse(Files.exists(csv));
	}

	@Test
	public void dropdownSelectionsSynchroniseCodeAndClassification(FxRobot robot) {
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.interact(() -> units.getSelectionModel().select(unit3));
		assertEquals("3", codeField.getText());
		robot.interact(() -> topics.getSelectionModel().select(topic31));
		assertEquals("3.1", codeField.getText());
		robot.interact(() -> subtopics.getSelectionModel().select(subtopic311));
		assertEquals("3.1.1", codeField.getText());
		assertEquals(subtopic311, pane.selectedClassificationProperty().get());
		robot.interact(() -> descriptors.getSelectionModel().select(descriptor3111));
		assertEquals("3.1.1.1", codeField.getText());
		assertEquals(descriptor3111, pane.selectedClassificationProperty().get());
	}

	@Test
	public void editingDescriptorCodeBackToSubtopicClearsDescriptor(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.clickOn(codeField).write("3.1.1.1");
		robot.eraseText(2);
		assertEquals("3.1.1", codeField.getText());
		assertEquals(subtopic311, model.getSubtopic());
		assertNull(model.getDescriptor());
		assertEquals(subtopic311, pane.selectedClassificationProperty().get());
	}

	@Test
	public void hierarchySelectionRecordsCorrelatedDiagnostics(FxRobot robot) throws Exception {
		Path csv = Files.createTempDirectory("curriculum-selection-performance-").resolve("performance.csv");
		PerformanceRecorder recorder = new PerformanceRecorder(true, csv);
		robot.interact(() -> pane.setPerformanceRecorder(recorder));
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		robot.interact(() -> {
			units.getSelectionModel().select(unit3);
			topics.getSelectionModel().select(topic31);
			subtopics.getSelectionModel().select(subtopic311);
			descriptors.getSelectionModel().select(descriptor3111);
		});
		assertEquals(descriptor3111, model.getClassification());
		assertTrue(Files.isRegularFile(csv));
		List<String[]> rows = Files.readAllLines(csv).stream().skip(1).map(line -> line.split(",", -1)).toList();
		for (String operationName : List.of("capture.curriculum.unit", "capture.curriculum.topic",
				"capture.curriculum.subtopic", "capture.curriculum.descriptor")) {
			assertTrue(rows.stream().anyMatch(row -> row[3].equals(operationName) && row[5].equals("true")),
					operationName);
		}
		for (String[] pair : List.of(new String[] { "capture.curriculum.unit", "capture.curriculum.topics.lookup" },
				new String[] { "capture.curriculum.topic", "capture.curriculum.subtopics.lookup" },
				new String[] { "capture.curriculum.subtopic", "capture.curriculum.descriptors.lookup" })) {
			String[] parent = rows.stream().filter(row -> row[3].equals(pair[0])).findFirst().orElseThrow();
			assertTrue(
					rows.stream().anyMatch(
							row -> row[3].equals(pair[1]) && row[2].equals(parent[1]) && row[5].equals("true")),
					"Missing correlated lookup: " + pair[1]);
		}
	}

	@Test
	public void leafDescriptorRejectsTrailingPeriod(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		robot.clickOn(codeField).write("3.1.1.1");
		assertEquals("3.1.1.1", codeField.getText());
		robot.write(".");
		assertEquals("3.1.1.1", codeField.getText());
	}

	@Test
	public void selectingExistingClassificationSynchronisesCodeField(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);

		// Restoring a persisted classification must reconstruct the complete visible
		// path, not merely the final model classification and code field.
		robot.interact(() -> pane.selectClassificationPath(descriptor3111));
		assertEquals("3.1.1.1", codeField.getText());
		assertEquals(unit3, units.getValue());
		assertEquals(topic31, topics.getValue());
		assertEquals(subtopic311, subtopics.getValue());
		assertEquals(descriptor3111, descriptors.getValue());
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(descriptor3111, model.getDescriptor());
	}

	@Test
	public void shorteningCompleteCodeToUnitClearsPreviousTopicAndClassification(FxRobot robot) {
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		@SuppressWarnings("unchecked")
		ComboBox<Unit> units = robot.lookup("#curriculum-unit").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Topic> topics = robot.lookup("#curriculum-topic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Subtopic> subtopics = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<Descriptor> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);

		// Establish the stale-state scenario reported during real Question capture.
		robot.interact(() -> codeField.setText("3.1.1.1"));
		assertEquals(topic31, topics.getValue());
		assertEquals(descriptor3111, descriptors.getValue());

		// Replacing the complete path with its Unit must retain only that Unit. No
		// value from the previous deeper path may survive in a ComboBox or the model.
		robot.interact(() -> codeField.setText("3"));
		assertEquals("3", codeField.getText());
		assertEquals(unit3, units.getValue());
		assertNull(topics.getValue());
		assertNull(subtopics.getValue());
		assertNull(descriptors.getValue());
		assertEquals(unit3, model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getSubtopic());
		assertNull(model.getDescriptor());
		assertNull(model.getClassification());
	}

	@Start
	public void start(Stage stage) {
		chemistry = new Subject(1, "Chemistry");
		syllabus2019 = new SyllabusVersion(2, chemistry, "2019", false);
		syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);
		unit3 = new Unit(1, syllabus2025, "3", "Unit 3", 1);
		topic31 = new Topic(2, syllabus2025, unit3, "3.1", "Topic 3.1", 1);
		subtopic311 = new Subtopic(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", 1);
		descriptor3111 = new Descriptor(4, syllabus2025, subtopic311, "3.1.1.1", "Descriptor 3.1.1.1", 1);
		topic32 = new Topic(5, syllabus2025, unit3, "3.2", "Topic 3.2", 2);
		descriptor321 = new Descriptor(6, syllabus2025, topic32, "3.2.1", "Descriptor 3.2.1", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2019, syllabus2025),
				List.of(unit3, topic31, subtopic311, descriptor3111, topic32, descriptor321));
		model = new CurriculumSelectionModel(repository);
		pane = new CurriculumSelectorPane(model);
		pane.selectSubject(chemistry);
		stage.setScene(new Scene(pane, 700, 420));
		stage.show();
	}

	@Test
	public void subjectEditingCanBeDisabledOutsideDashboard(FxRobot robot) {
		ComboBox<?> subjects = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		Button addSubject = robot.lookup("#add-subject").queryAs(Button.class);
		Label readOnlySubject = robot.lookup("#working-subject-value").queryAs(Label.class);
		robot.interact(() -> pane.setSubjectEditingEnabled(false));

		// Specialised work retains clear Subject context but exposes neither a selector
		// nor the Subject-creation action.
		assertFalse(subjects.isVisible());
		assertFalse(subjects.isManaged());
		assertFalse(addSubject.isVisible());
		assertFalse(addSubject.isManaged());
		assertTrue(readOnlySubject.isVisible());
		assertTrue(readOnlySubject.isManaged());
		assertEquals("Chemistry", readOnlySubject.getText());
		robot.interact(() -> pane.setSubjectEditingEnabled(true));
		assertTrue(subjects.isVisible());
		assertTrue(subjects.isManaged());
		assertTrue(addSubject.isVisible());
		assertTrue(addSubject.isManaged());
		assertFalse(readOnlySubject.isVisible());
		assertFalse(readOnlySubject.isManaged());
	}

	@Test
	public void subtopicAndDescriptorRowsFollowSelectedHierarchy(FxRobot robot) {
		ComboBox<?> subtopicBox = robot.lookup("#curriculum-subtopic").queryAs(ComboBox.class);
		ComboBox<?> descriptorBox = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		robot.clickOn("#curriculum-code").write("3.1");
		assertTrue(subtopicBox.isVisible());
		assertFalse(descriptorBox.isVisible());
		robot.write(".1");
		assertTrue(subtopicBox.isVisible());
		assertTrue(descriptorBox.isVisible());
		assertEquals(subtopic311, model.getClassification());
	}

	@Test
	public void syllabusContextCanBeLockedWhileClassificationRemainsEditable(FxRobot robot) {
		robot.interact(() -> pane.selectClassificationPath(subtopic311));
		robot.interact(() -> pane.setSyllabusContextLocked(true));
		ComboBox<?> subjects = robot.lookup("#curriculum-subject").queryAs(ComboBox.class);
		ComboBox<?> syllabuses = robot.lookup("#curriculum-syllabus").queryAs(ComboBox.class);
		ComboBox<?> descriptors = robot.lookup("#curriculum-descriptor").queryAs(ComboBox.class);
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		assertTrue(subjects.isDisabled());
		assertTrue(syllabuses.isDisabled());
		assertFalse(codeField.isDisabled());
		assertFalse(descriptors.isDisabled());
		robot.interact(() -> descriptors.getSelectionModel().select(descriptors.getItems().indexOf(descriptor3111)));
		assertEquals(descriptor3111, model.getClassification());
		assertEquals("3.1.1.1", codeField.getText());
		robot.interact(() -> pane.setSyllabusContextLocked(false));
		assertFalse(subjects.isDisabled());
		assertFalse(syllabuses.isDisabled());
	}

	@Test
	public void unmatchedSuffixRetainsValidParentSelection(FxRobot robot) {
		robot.clickOn("#curriculum-code").write("3.1");
		assertEquals(topic31, model.getTopic());
		robot.write(".9");
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertNull(model.getClassification());
	}
}
