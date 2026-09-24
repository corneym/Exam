package au.edu.eq.questionbank.ui.export;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * Collects Subject, Unit, grouping and destination choices for an HTML revision
 * export.
 */
public final class RevisionExportDialog extends Dialog<ButtonType> {

	private static final int FILE_CONTROL_SPACING = 6;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private static final int UNIT_SPACING = 4;
	private static final double UNIT_LIST_HEIGHT = 120;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<RevisionGroupingMode> groupingBox = new ComboBox<>();
	private final TextField destinationField = new TextField();
	private final VBox unitBox = new VBox(UNIT_SPACING);
	private final Function<Subject, List<Unit>> exportableUnits;
	private final BiPredicate<Subject, Set<Long>> descriptorGroupingAvailable;
	private final boolean unitSelectionEnabled;
	private Path destinationParent;
	private final Button exportButton;

	/**
	 * Creates the legacy all-Units dialog with Subject-level grouping capability.
	 *
	 * @param owner          owner window
	 * @param subjects       Subjects available for export
	 * @param defaultSubject initially selected Subject, or {@code null}
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject) {
		this(owner, subjects, defaultSubject, _ -> false);
	}

	/**
	 * Creates a dialog with Unit selection and scope-aware grouping capability.
	 *
	 * @param owner                       owner window
	 * @param subjects                    Subjects available for export
	 * @param defaultSubject              initially selected Subject, or
	 *                                    {@code null}
	 * @param exportableUnits             lookup for Units containing renderable
	 *                                    content
	 * @param descriptorGroupingAvailable capability check for the selected Unit
	 *                                    scope
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject,
			Function<Subject, List<Unit>> exportableUnits,
			BiPredicate<Subject, Set<Long>> descriptorGroupingAvailable) {
		this(owner, subjects, defaultSubject, exportableUnits, descriptorGroupingAvailable, true);
	}

	/**
	 * Creates the legacy all-Units dialog with a grouping capability check.
	 *
	 * @param owner                       owner window
	 * @param subjects                    Subjects available for export
	 * @param defaultSubject              initially selected Subject, or
	 *                                    {@code null}
	 * @param descriptorGroupingAvailable Subject-level grouping capability check
	 */
	public RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject,
			Predicate<Subject> descriptorGroupingAvailable) {
		this(owner, subjects, defaultSubject, _ -> List.of(), (subject, _) -> descriptorGroupingAvailable.test(subject),
				false);
	}

	private RevisionExportDialog(Window owner, List<Subject> subjects, Subject defaultSubject,
			Function<Subject, List<Unit>> exportableUnits, BiPredicate<Subject, Set<Long>> descriptorGroupingAvailable,
			boolean unitSelectionEnabled) {
		if (subjects == null) {
			throw new NullPointerException("subjects");
		}
		if (exportableUnits == null) {
			throw new NullPointerException("exportableUnits");
		}
		if (descriptorGroupingAvailable == null) {
			throw new NullPointerException("descriptorGroupingAvailable");
		}
		for (Subject subject : subjects) {
			if (subject == null) {
				throw new NullPointerException("subjects contains null");
			}
		}
		this.exportableUnits = exportableUnits;
		this.descriptorGroupingAvailable = descriptorGroupingAvailable;
		this.unitSelectionEnabled = unitSelectionEnabled;
		setTitle("Export Revision HTML");
		setHeaderText("Create a student revision website");
		initOwner(owner);
		ButtonType exportButtonType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
		subjectBox.setId("revision-export-subject");
		subjectBox.setPromptText("Select subject");
		subjectBox.getItems().setAll(subjects);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		groupingBox.setId("revision-export-grouping");
		groupingBox.setPromptText("Select grouping");
		groupingBox.setMaxWidth(Double.MAX_VALUE);
		destinationField.setId("revision-export-destination");
		destinationField.setEditable(false);
		destinationField.setPromptText("Choose destination folder");
		Button browseButton = new Button("Browse...");
		browseButton.setId("revision-export-browse");
		browseButton.setOnAction(_ -> chooseDestination(owner));
		HBox destinationBox = new HBox(FILE_CONTROL_SPACING, destinationField, browseButton);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectBox, 1, 0);
		int groupingRow;
		if (unitSelectionEnabled) {
			ScrollPane unitScrollPane = new ScrollPane(unitBox);
			unitScrollPane.setId("revision-export-units");
			unitScrollPane.setFitToWidth(true);
			unitScrollPane.setPrefViewportHeight(UNIT_LIST_HEIGHT);
			grid.add(new Label("Units to include:"), 0, 1);
			grid.add(unitScrollPane, 1, 1);
			groupingRow = 2;
		} else {
			groupingRow = 1;
		}
		grid.add(new Label("Group questions by:"), 0, groupingRow);
		grid.add(groupingBox, 1, groupingRow);
		grid.add(new Label("Destination parent:"), 0, groupingRow + 1);
		grid.add(destinationBox, 1, groupingRow + 1);
		getDialogPane().setContent(grid);
		exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
		exportButton.setId("revision-export-start");
		exportButton.setDisable(true);
		subjectBox.valueProperty().addListener((_, _, _) -> {
			refreshUnits();
			refreshGroupingModes();
			updateExportButton();
		});
		groupingBox.valueProperty().addListener((_, _, _) -> updateExportButton());
		if (defaultSubject != null && subjectBox.getItems().contains(defaultSubject)) {
			subjectBox.setValue(defaultSubject);
		} else {
			refreshUnits();
			refreshGroupingModes();
		}
		updateExportButton();
	}

	/**
	 * Returns the selected parent directory for the generated site.
	 *
	 * @return selected parent directory
	 */
	public Path getDestinationParent() {
		return destinationParent;
	}

	/**
	 * Returns the selected presentation grouping mode.
	 *
	 * @return selected grouping mode
	 */
	public RevisionGroupingMode getGroupingMode() {
		return groupingBox.getValue();
	}

	/**
	 * Returns the selected Subject.
	 *
	 * @return selected Subject, or {@code null} when none is selected
	 */
	public Subject getSelectedSubject() {
		return subjectBox.getValue();
	}

	/**
	 * Returns the selected Unit identifiers.
	 *
	 * @return immutable identifiers of selected Units
	 */
	public Set<Long> getSelectedUnitIds() {
		Set<Long> selectedIds = new LinkedHashSet<>();
		for (Node node : unitBox.getChildren()) {
			if (!(node instanceof CheckBox checkBox) || !checkBox.isSelected()
					|| !(checkBox.getUserData() instanceof Unit unit)) {
				continue;
			}
			selectedIds.add(unit.getId());
		}
		return Set.copyOf(selectedIds);
	}

	private void chooseDestination(Window owner) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Choose Revision Export Destination");
		if (destinationParent != null) {
			File current = destinationParent.toFile();
			if (current.isDirectory()) {
				chooser.setInitialDirectory(current);
			}
		}
		File selected = chooser.showDialog(owner);
		if (selected != null) {
			setDestinationParent(selected.toPath());
		}
	}

	private void refreshGroupingModes() {
		Subject subject = subjectBox.getValue();
		RevisionGroupingMode previous = groupingBox.getValue();
		if (subject == null) {
			groupingBox.getItems().clear();
			groupingBox.setValue(null);
			return;
		}
		Set<Long> selectedUnitIds = getSelectedUnitIds();
		if (unitSelectionEnabled && selectedUnitIds.isEmpty()) {
			groupingBox.getItems().clear();
			groupingBox.setValue(null);
			return;
		}
		boolean descriptorAvailable = descriptorGroupingAvailable.test(subject, selectedUnitIds);
		if (descriptorAvailable) {
			groupingBox.getItems().setAll(RevisionGroupingMode.DESCRIPTOR, RevisionGroupingMode.SUBTOPIC);
		} else {
			groupingBox.getItems().setAll(RevisionGroupingMode.SUBTOPIC);
		}
		if (previous != null && groupingBox.getItems().contains(previous)) {
			groupingBox.setValue(previous);
		} else if (descriptorAvailable) {
			groupingBox.setValue(RevisionGroupingMode.DESCRIPTOR);
		} else {
			groupingBox.setValue(RevisionGroupingMode.SUBTOPIC);
		}
	}

	private void refreshUnits() {
		unitBox.getChildren().clear();
		if (!unitSelectionEnabled) {
			return;
		}
		Subject subject = subjectBox.getValue();
		if (subject == null) {
			return;
		}
		List<Unit> units = exportableUnits.apply(subject);
		if (units == null) {
			throw new IllegalStateException("Exportable Unit provider returned null");
		}
		if (units.isEmpty()) {
			unitBox.getChildren().add(new Label("No Units contain revision questions."));
			return;
		}
		for (Unit unit : units) {
			if (unit == null) {
				throw new IllegalStateException("Exportable Unit provider returned null");
			}
			if (!subject.equals(unit.getSyllabusVersion().getSubject())) {
				throw new IllegalStateException("Exportable Unit belongs to another Subject");
			}
			CheckBox checkBox = new CheckBox(unit.toString());
			checkBox.setId("revision-export-unit-" + unit.getId());
			checkBox.setUserData(unit);

			// Every non-empty Unit is included by default.
			checkBox.setSelected(true);
			checkBox.selectedProperty().addListener((_, _, _) -> {
				refreshGroupingModes();
				updateExportButton();
			});
			unitBox.getChildren().add(checkBox);
		}
	}

	private void setDestinationParent(Path destinationParent) {
		if (destinationParent == null) {
			throw new NullPointerException("destinationParent");
		}
		Path normalized = destinationParent.toAbsolutePath().normalize();
		this.destinationParent = normalized;
		destinationField.setText(normalized.toString());
		updateExportButton();
	}

	private void updateExportButton() {
		boolean missingUnitSelection = unitSelectionEnabled && getSelectedUnitIds().isEmpty();
		exportButton.setDisable(subjectBox.getValue() == null || groupingBox.getValue() == null
				|| destinationParent == null || missingUnitSelection);
	}
}
