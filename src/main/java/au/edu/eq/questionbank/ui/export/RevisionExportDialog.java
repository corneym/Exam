package au.edu.eq.questionbank.ui.export;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/**
 * Collects Unit, grouping and destination choices for a HTML export scoped to
 * the authoritative Working Subject.
 */
public final class RevisionExportDialog extends Dialog<ButtonType> {

	private static final int FILE_CONTROL_SPACING = 6;
	private static final int FORM_COLUMN_GAP = 10;
	private static final int FORM_ROW_GAP = 8;
	private static final int FORM_PADDING = 10;
	private static final int UNIT_SPACING = 4;
	private static final double UNIT_LIST_HEIGHT = 120;
	private final ComboBox<RevisionGroupingMode> groupingBox = new ComboBox<>();
	private final TextField destinationField = new TextField();
	private final VBox unitBox = new VBox(UNIT_SPACING);
	private final Function<Subject, List<Unit>> exportableUnits;
	private final BiPredicate<Subject, Set<Long>> descriptorGroupingAvailable;
	private Path destinationParent;
	private final Button exportButton;
	private final Subject subject;
	private final Label subjectLabel = new Label();

	/**
	 * Creates a Revision HTML export dialog scoped to the authoritative Working
	 * Subject.
	 *
	 * @param owner                       owner window
	 * @param subject                     authoritative Working Subject
	 * @param exportableUnits             lookup for Units containing renderable
	 *                                    content
	 * @param descriptorGroupingAvailable capability check for the selected Unit
	 *                                    scope
	 * @throws NullPointerException if {@code subject}, {@code exportableUnits} or
	 *                              {@code descriptorGroupingAvailable} is
	 *                              {@code null}
	 */
	public RevisionExportDialog(Window owner, Subject subject, Function<Subject, List<Unit>> exportableUnits,
			BiPredicate<Subject, Set<Long>> descriptorGroupingAvailable) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (exportableUnits == null) {
			throw new NullPointerException("exportableUnits");
		}
		if (descriptorGroupingAvailable == null) {
			throw new NullPointerException("descriptorGroupingAvailable");
		}
		this.subject = subject;
		this.exportableUnits = exportableUnits;
		this.descriptorGroupingAvailable = descriptorGroupingAvailable;
		setTitle("Export Revision HTML");
		setHeaderText("Create a student revision website");
		initOwner(owner);
		ButtonType exportButtonType = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(exportButtonType, ButtonType.CANCEL);
		subjectLabel.setId("revision-export-subject");
		subjectLabel.setText(subject.getName());
		groupingBox.setId("revision-export-grouping");
		groupingBox.setPromptText("Select grouping");
		groupingBox.setMaxWidth(Double.MAX_VALUE);
		groupingBox.setTooltip(new Tooltip(
				"Choose how Questions are organised in the generated revision site; stored curriculum and Question data are unchanged."));
		destinationField.setId("revision-export-destination");
		destinationField.setEditable(false);
		destinationField.setPromptText("Choose destination folder");
		Button browseButton = new Button("Browse...");
		browseButton.setId("revision-export-browse");
		browseButton.setOnAction(_ -> chooseDestination(owner));
		HBox destinationBox = new HBox(FILE_CONTROL_SPACING, destinationField, browseButton);
		ScrollPane unitScrollPane = new ScrollPane(unitBox);
		unitScrollPane.setId("revision-export-units");
		unitScrollPane.setFitToWidth(true);
		unitScrollPane.setPrefViewportHeight(UNIT_LIST_HEIGHT);
		GridPane grid = new GridPane();
		grid.setHgap(FORM_COLUMN_GAP);
		grid.setVgap(FORM_ROW_GAP);
		grid.setPadding(new Insets(FORM_PADDING));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectLabel, 1, 0);
		grid.add(new Label("Units to include:"), 0, 1);
		grid.add(unitScrollPane, 1, 1);
		grid.add(new Label("Group questions by:"), 0, 2);
		grid.add(groupingBox, 1, 2);
		grid.add(new Label("Destination parent:"), 0, 3);
		grid.add(destinationBox, 1, 3);
		getDialogPane().setContent(grid);
		exportButton = (Button) getDialogPane().lookupButton(exportButtonType);
		exportButton.setId("revision-export-start");
		exportButton.setDisable(true);
		groupingBox.valueProperty().addListener((_, _, _) -> updateExportButton());

		// The Working Subject is fixed by the application, so initialise only its
		// Unit and grouping choices rather than exposing another Subject selector.
		refreshUnits();
		refreshGroupingModes();
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
	 * Returns the authoritative Working Subject for this export.
	 *
	 * @return fixed export Subject
	 */
	public Subject getSelectedSubject() {
		return subject;
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
		RevisionGroupingMode previous = groupingBox.getValue();
		Set<Long> selectedUnitIds = getSelectedUnitIds();
		if (selectedUnitIds.isEmpty()) {
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
			checkBox.setTooltip(new Tooltip(
					"Include this Unit in the generated revision site; the selection also limits available grouping options."));
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
		exportButton.setDisable(
				groupingBox.getValue() == null || destinationParent == null || getSelectedUnitIds().isEmpty());
	}
}
