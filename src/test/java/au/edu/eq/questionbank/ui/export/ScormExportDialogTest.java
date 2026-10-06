package au.edu.eq.questionbank.ui.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

@Tag("ui")
@ExtendWith(ApplicationExtension.class)
class ScormExportDialogTest {

	private Stage stage;

	@Test
	void defaultsToCurrentSubjectAndRequiresDestination() throws Exception {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(1, chemistry, "2025", true);
		Unit unit = new Unit(10, syllabus, "1", "Unit 1", 1);

		ScormExportDialog[] holder = new ScormExportDialog[1];
		org.testfx.api.FxToolkit.setupFixture(
				() -> holder[0] = new ScormExportDialog(stage, chemistry, _ -> List.of(unit), (_, _) -> true));

		ScormExportDialog dialog = holder[0];
		Node subjectControl = dialog.getDialogPane().lookup("#scorm-export-subject");
		Field exportButtonField = ScormExportDialog.class.getDeclaredField("exportButton");
		exportButtonField.setAccessible(true);
		Button exportButton = (Button) exportButtonField.get(dialog);

		// SCORM inherits the same application-owned Working Subject as HTML export.
		assertTrue(subjectControl instanceof Label);
		assertEquals("Chemistry", ((Label) subjectControl).getText());
		assertEquals(chemistry, dialog.getSelectedSubject());

		assertNull(dialog.getDestinationParent());
		assertTrue(exportButton.isDisabled());

		Path destination = Path.of("target", "scorm-export-test").toAbsolutePath().normalize();
		Method setDestination = ScormExportDialog.class.getDeclaredMethod("setDestinationParent", Path.class);
		setDestination.setAccessible(true);

		org.testfx.api.FxToolkit.setupFixture(() -> {
			try {
				setDestination.invoke(dialog, destination);
			} catch (ReflectiveOperationException exception) {
				throw new RuntimeException(exception);
			}
		});
		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(destination, dialog.getDestinationParent());
		assertFalse(exportButton.isDisabled());
	}

	@Test
	void descriptorGroupingIsOfferedOnlyWhenSubjectHasCompleteCoverage() throws Exception {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion chemistrySyllabus = new SyllabusVersion(1, chemistry, "2025", true);
		Unit chemistryUnit = new Unit(10, chemistrySyllabus, "1", "Chemistry Unit", 1);

		ScormExportDialog[] chemistryHolder = new ScormExportDialog[1];
		org.testfx.api.FxToolkit.setupFixture(() -> chemistryHolder[0] = new ScormExportDialog(stage, chemistry,
				_ -> List.of(chemistryUnit), (_, _) -> true));

		ScormExportDialog chemistryDialog = chemistryHolder[0];
		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> chemistryGroupingBox = (ComboBox<RevisionGroupingMode>) chemistryDialog
				.getDialogPane().lookup("#scorm-export-grouping");

		assertEquals(List.of(RevisionGroupingMode.DESCRIPTOR, RevisionGroupingMode.SUBTOPIC),
				chemistryGroupingBox.getItems());
		assertEquals(RevisionGroupingMode.DESCRIPTOR, chemistryDialog.getGroupingMode());

		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physicsSyllabus = new SyllabusVersion(2, physics, "2025", true);
		Unit physicsUnit = new Unit(20, physicsSyllabus, "1", "Physics Unit", 1);

		ScormExportDialog[] physicsHolder = new ScormExportDialog[1];
		org.testfx.api.FxToolkit.setupFixture(() -> physicsHolder[0] = new ScormExportDialog(stage, physics,
				_ -> List.of(physicsUnit), (_, _) -> false));

		ScormExportDialog physicsDialog = physicsHolder[0];
		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> physicsGroupingBox = (ComboBox<RevisionGroupingMode>) physicsDialog
				.getDialogPane().lookup("#scorm-export-grouping");

		assertEquals(List.of(RevisionGroupingMode.SUBTOPIC), physicsGroupingBox.getItems());
		assertEquals(RevisionGroupingMode.SUBTOPIC, physicsDialog.getGroupingMode());
		assertEquals(physics, physicsDialog.getSelectedSubject());
	}

	@Test
	void selectsAllNonEmptyUnitsAndRechecksGroupingForSubset() throws Exception {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(1, chemistry, "2025", true);
		Unit completeUnit = new Unit(10, syllabus, "1", "Complete Unit", 1);
		Unit incompleteUnit = new Unit(20, syllabus, "2", "Incomplete Unit", 2);

		ScormExportDialog[] holder = new ScormExportDialog[1];
		org.testfx.api.FxToolkit.setupFixture(
				() -> holder[0] = new ScormExportDialog(stage, chemistry, _ -> List.of(completeUnit, incompleteUnit),
						(_, selectedUnitIds) -> selectedUnitIds.equals(Set.of(completeUnit.getId()))));

		ScormExportDialog dialog = holder[0];

		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> groupingBox = (ComboBox<RevisionGroupingMode>) dialog.getDialogPane()
				.lookup("#scorm-export-grouping");

		assertEquals(Set.of(completeUnit.getId(), incompleteUnit.getId()), dialog.getSelectedUnitIds());
		assertEquals(List.of(RevisionGroupingMode.SUBTOPIC), groupingBox.getItems());

		Field unitBoxField = ScormExportDialog.class.getDeclaredField("unitBox");
		unitBoxField.setAccessible(true);
		VBox unitBox = (VBox) unitBoxField.get(dialog);

		CheckBox incompleteUnitBox = unitBox.getChildren().stream()
				.filter(node -> "scorm-export-unit-20".equals(node.getId())).map(node -> (CheckBox) node).findFirst()
				.orElseThrow();

		assertNotNull(groupingBox.getTooltip());
		assertTrue(groupingBox.getTooltip().getText().contains("stored curriculum and Question data are unchanged"));
		assertNotNull(incompleteUnitBox.getTooltip());
		assertTrue(incompleteUnitBox.getTooltip().getText().contains("generated SCORM package"));

		org.testfx.api.FxToolkit.setupFixture(() -> incompleteUnitBox.setSelected(false));
		WaitForAsyncUtils.waitForFxEvents();

		assertEquals(Set.of(completeUnit.getId()), dialog.getSelectedUnitIds());
		assertEquals(List.of(RevisionGroupingMode.DESCRIPTOR, RevisionGroupingMode.SUBTOPIC), groupingBox.getItems());
	}

	@Start
	void start(Stage stage) {
		this.stage = stage;
		stage.setScene(new Scene(new StackPane(), 400, 300));
		stage.show();
	}
}
