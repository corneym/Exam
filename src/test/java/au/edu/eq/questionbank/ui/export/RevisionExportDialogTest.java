package au.edu.eq.questionbank.ui.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testfx.api.FxToolkit.setupFixture;

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
class RevisionExportDialogTest {

	private Stage stage;

	@Test
	void defaultsToCurrentSubjectAndRequiresDestination() throws Exception {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(1, chemistry, "2025", true);
		Unit unit = new Unit(10, syllabus, "1", "Unit 1", 1);

		RevisionExportDialog[] holder = new RevisionExportDialog[1];
		setupFixture(() -> holder[0] = new RevisionExportDialog(stage, chemistry, _ -> List.of(unit), (_, _) -> true));

		RevisionExportDialog dialog = holder[0];
		Node subjectControl = dialog.getDialogPane().lookup("#revision-export-subject");
		Field exportButtonField = RevisionExportDialog.class.getDeclaredField("exportButton");
		exportButtonField.setAccessible(true);
		Button exportButton = (Button) exportButtonField.get(dialog);

		// Subject is inherited from application context and is presentation-only inside
		// the export dialog.
		assertTrue(subjectControl instanceof Label);
		assertEquals("Chemistry", ((Label) subjectControl).getText());
		assertEquals(chemistry, dialog.getSelectedSubject());

		assertNull(dialog.getDestinationParent());
		assertTrue(exportButton.isDisabled());

		Path destination = Path.of("target", "revision-export-test").toAbsolutePath().normalize();
		Method setDestination = RevisionExportDialog.class.getDeclaredMethod("setDestinationParent", Path.class);
		setDestination.setAccessible(true);

		setupFixture(() -> {
			try {
				setDestination.invoke(dialog, destination);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
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

		RevisionExportDialog[] chemistryHolder = new RevisionExportDialog[1];
		setupFixture(() -> chemistryHolder[0] = new RevisionExportDialog(stage, chemistry, _ -> List.of(chemistryUnit),
				(_, _) -> true));

		RevisionExportDialog chemistryDialog = chemistryHolder[0];
		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> chemistryGroupingBox = (ComboBox<RevisionGroupingMode>) chemistryDialog
				.getDialogPane().lookup("#revision-export-grouping");

		assertEquals(List.of(RevisionGroupingMode.DESCRIPTOR, RevisionGroupingMode.SUBTOPIC),
				chemistryGroupingBox.getItems());
		assertEquals(RevisionGroupingMode.DESCRIPTOR, chemistryDialog.getGroupingMode());

		Subject physics = new Subject(2, "Physics");
		SyllabusVersion physicsSyllabus = new SyllabusVersion(2, physics, "2025", true);
		Unit physicsUnit = new Unit(20, physicsSyllabus, "1", "Physics Unit", 1);

		RevisionExportDialog[] physicsHolder = new RevisionExportDialog[1];
		setupFixture(() -> physicsHolder[0] = new RevisionExportDialog(stage, physics, _ -> List.of(physicsUnit),
				(_, _) -> false));

		RevisionExportDialog physicsDialog = physicsHolder[0];
		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> physicsGroupingBox = (ComboBox<RevisionGroupingMode>) physicsDialog
				.getDialogPane().lookup("#revision-export-grouping");

		// Descriptor grouping remains capability-driven, but Subject can no longer be
		// changed inside the export dialog.
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

		RevisionExportDialog[] holder = new RevisionExportDialog[1];
		setupFixture(
				() -> holder[0] = new RevisionExportDialog(stage, chemistry, _ -> List.of(completeUnit, incompleteUnit),
						(_, selectedUnitIds) -> selectedUnitIds.equals(Set.of(completeUnit.getId()))));

		RevisionExportDialog dialog = holder[0];

		@SuppressWarnings("unchecked")
		ComboBox<RevisionGroupingMode> groupingBox = (ComboBox<RevisionGroupingMode>) dialog.getDialogPane()
				.lookup("#revision-export-grouping");

		assertEquals(Set.of(completeUnit.getId(), incompleteUnit.getId()), dialog.getSelectedUnitIds());
		assertEquals(List.of(RevisionGroupingMode.SUBTOPIC), groupingBox.getItems());

		Field unitBoxField = RevisionExportDialog.class.getDeclaredField("unitBox");
		unitBoxField.setAccessible(true);
		VBox unitBox = (VBox) unitBoxField.get(dialog);

		CheckBox incompleteUnitBox = unitBox.getChildren().stream()
				.filter(node -> "revision-export-unit-20".equals(node.getId())).map(node -> (CheckBox) node).findFirst()
				.orElseThrow();

		assertNotNull(groupingBox.getTooltip());
		assertTrue(groupingBox.getTooltip().getText().contains("stored curriculum and Question data are unchanged"));
		assertNotNull(incompleteUnitBox.getTooltip());
		assertTrue(incompleteUnitBox.getTooltip().getText().contains("generated revision site"));

		setupFixture(() -> incompleteUnitBox.setSelected(false));
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
