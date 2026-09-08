package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.curriculum.InMemoryCurriculumRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
public class CurriculumSelectorPaneTest {

	private Subject chemistry;
	private Unit unit3;
	private Topic topic31;
	private Subtopic subtopic311;
	private Descriptor descriptor3111;
	private Topic topic32;
	private Descriptor descriptor321;
	private CurriculumSelectionModel model;
	private CurriculumSelectorPane pane;

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
		robot.clickOn("#curriculum-code").write("3");
		assertEquals(unit3, model.getUnit());
		assertNull(model.getTopic());
		assertNull(model.getClassification());
		robot.write(".1");
		assertEquals(topic31, model.getTopic());
		assertNull(model.getClassification());
		robot.write(".1");
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(subtopic311, model.getClassification());
		robot.write(".1");
		assertEquals(descriptor3111, model.getDescriptor());
		assertEquals(descriptor3111, model.getClassification());
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
	public void selectingExistingClassificationSynchronisesCodeField(FxRobot robot) {
		robot.interact(() -> pane.selectClassificationPath(descriptor3111));
		TextField codeField = robot.lookup("#curriculum-code").queryAs(TextField.class);
		assertEquals("3.1.1.1", codeField.getText());
		assertEquals(subtopic311, model.getSubtopic());
		assertEquals(descriptor3111, model.getDescriptor());
	}

	@Start
	public void start(Stage stage) {
		chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus2025 = new SyllabusVersion(1, chemistry, "2025", true);
		unit3 = new Unit(1, syllabus2025, "3", "Unit 3", 1);
		topic31 = new Topic(2, syllabus2025, unit3, "3.1", "Topic 3.1", 1);
		subtopic311 = new Subtopic(3, syllabus2025, topic31, "3.1.1", "Subtopic 3.1.1", 1);
		descriptor3111 = new Descriptor(4, syllabus2025, subtopic311, "3.1.1.1", "Descriptor 3.1.1.1", 1);
		topic32 = new Topic(5, syllabus2025, unit3, "3.2", "Topic 3.2", 2);
		descriptor321 = new Descriptor(6, syllabus2025, topic32, "3.2.1", "Descriptor 3.2.1", 1);
		InMemoryCurriculumRepository repository = new InMemoryCurriculumRepository(List.of(chemistry),
				List.of(syllabus2025), List.of(unit3, topic31, subtopic311, descriptor3111, topic32, descriptor321));
		model = new CurriculumSelectionModel(repository);
		pane = new CurriculumSelectorPane(model);
		pane.selectSubject(chemistry);
		stage.setScene(new Scene(pane, 700, 420));
		stage.show();
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
	public void unmatchedSuffixRetainsValidParentSelection(FxRobot robot) {
		robot.clickOn("#curriculum-code").write("3.1");
		assertEquals(topic31, model.getTopic());
		robot.write(".9");
		assertEquals(unit3, model.getUnit());
		assertEquals(topic31, model.getTopic());
		assertNull(model.getClassification());
	}
}
