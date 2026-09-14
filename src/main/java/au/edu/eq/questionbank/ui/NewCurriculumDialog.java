package au.edu.eq.questionbank.ui;

import java.util.List;

import au.edu.eq.questionbank.model.Subject;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * Modal form for creating an empty persisted syllabus ready for curriculum
 * authoring.
 */
final class NewCurriculumDialog extends Dialog<ButtonType> {

	private final RadioButton existingSubjectButton = new RadioButton("Existing subject");
	private final RadioButton newSubjectButton = new RadioButton("New subject");
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final TextField newSubjectField = new TextField();
	private final TextField versionField = new TextField();
	private final CheckBox currentCheckBox = new CheckBox();

	NewCurriculumDialog(Window owner, List<Subject> subjects) {
		if (subjects == null) {
			throw new NullPointerException("subjects");
		}
		setTitle("New Curriculum");
		setHeaderText("Create a syllabus for curriculum authoring");
		initOwner(owner);
		ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);
		ToggleGroup subjectMode = new ToggleGroup();
		existingSubjectButton.setToggleGroup(subjectMode);
		newSubjectButton.setToggleGroup(subjectMode);
		existingSubjectButton.setId("existing-curriculum-subject");
		newSubjectButton.setId("new-curriculum-subject");
		subjectBox.setId("new-curriculum-subject-box");
		newSubjectField.setId("new-curriculum-subject-name");
		versionField.setId("new-curriculum-version");
		currentCheckBox.setId("new-curriculum-current");
		subjectBox.getItems().setAll(subjects);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		newSubjectField.setPromptText("e.g. Engineering");
		versionField.setPromptText("e.g. 2025");
		if (subjects.isEmpty()) {
			existingSubjectButton.setDisable(true);
			newSubjectButton.setSelected(true);
		} else {
			existingSubjectButton.setSelected(true);
			subjectBox.getSelectionModel().selectFirst();
		}
		existingSubjectButton.selectedProperty().addListener((_, _, _) -> refreshSubjectMode());
		newSubjectButton.selectedProperty().addListener((_, _, _) -> refreshSubjectMode());
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.setPadding(new Insets(10));
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(existingSubjectButton, 1, 0);
		grid.add(subjectBox, 1, 1);
		grid.add(newSubjectButton, 1, 2);
		grid.add(newSubjectField, 1, 3);
		grid.add(new Label("Syllabus version:"), 0, 4);
		grid.add(versionField, 1, 4);
		grid.add(new Label("Current syllabus:"), 0, 5);
		grid.add(currentCheckBox, 1, 5);
		getDialogPane().setContent(grid);
		Button createButton = (Button) getDialogPane().lookupButton(createButtonType);
		createButton.setId("create-curriculum");
		createButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> validateCreateAction(event));
		refreshSubjectMode();
	}

	String getSubjectName() {
		if (newSubjectButton.isSelected()) {
			return newSubjectField.getText().strip();
		}
		Subject subject = subjectBox.getValue();
		return subject == null ? "" : subject.getName();
	}

	String getVersionName() {
		return versionField.getText().strip();
	}

	boolean isCurrent() {
		return currentCheckBox.isSelected();
	}

	private boolean isValid() {
		if (newSubjectButton.isSelected()) {
			if (getSubjectName().isBlank()) {
				newSubjectField.requestFocus();
				return false;
			}
		} else if (subjectBox.getValue() == null) {
			subjectBox.requestFocus();
			return false;
		}
		if (getVersionName().isBlank()) {
			versionField.requestFocus();
			return false;
		}
		return true;
	}

	private void refreshSubjectMode() {
		boolean creatingSubject = newSubjectButton.isSelected();
		subjectBox.setDisable(creatingSubject);
		newSubjectField.setDisable(!creatingSubject);
	}

	private void validateCreateAction(javafx.event.ActionEvent event) {
		if (!isValid()) {
			event.consume();
		}
	}
}
