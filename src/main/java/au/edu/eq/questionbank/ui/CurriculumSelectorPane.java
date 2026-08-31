package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * JavaFX controls for selecting a subject and syllabus, then navigating its unit
 * and topic hierarchy to a final classification. A subject's current syllabus
 * is the default when available; historical syllabuses remain selectable.
 */
public class CurriculumSelectorPane extends VBox {

	private final CurriculumSelectionModel model;

	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();
	private final ComboBox<SyllabusVersion> syllabusBox = new ComboBox<>();
	private boolean refreshingSubjects;

	/**
	 * Creates a selector bound to the supplied selection model.
	 *
	 * @param model the curriculum selection state and lookup model
	 */
	public CurriculumSelectorPane(CurriculumSelectionModel model) {
		this.model = model;

		setSpacing(4);
		setPadding(new Insets(8));
		setStyle("-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;");

		Label classificationLabel = new Label("Classification");
		classificationLabel.setStyle("-fx-font-weight: bold;");

		subjectBox.setId("curriculum-subject");
		subjectBox.setPromptText("Select subject");
		syllabusBox.setId("curriculum-syllabus");
		syllabusBox.setPromptText("Select syllabus");
		unitBox.setId("curriculum-unit");
		unitBox.setPromptText("Select unit");
		topicBox.setId("curriculum-topic");
		topicBox.setPromptText("Select topic");
		classificationBox.setId("curriculum-subtopic");
		classificationBox.setPromptText("Select subtopic or descriptor");

		subjectBox.setMaxWidth(Double.MAX_VALUE);
		unitBox.setMaxWidth(Double.MAX_VALUE);
		topicBox.setMaxWidth(Double.MAX_VALUE);
		classificationBox.setMaxWidth(Double.MAX_VALUE);
		syllabusBox.setMaxWidth(Double.MAX_VALUE);

		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		syllabusBox.setDisable(true);

		configureSelectionHandlers();
		refreshSubjects();
		getChildren().addAll(classificationLabel, new Label("Subject"), subjectBox, new Label("Syllabus"), syllabusBox,
				new Label("Unit"), unitBox, new Label("Topic"), topicBox, new Label("Subtopic / Descriptor"),
				classificationBox);
	}

	/**
	 * Clears unit, topic, and final-classification state while retaining the
	 * selected subject and syllabus. Unit selection remains disabled if no
	 * syllabus is selected.
	 */
	public void clearClassificationBelowSubject() {
		unitBox.getSelectionModel().clearSelection();
		topicBox.getSelectionModel().clearSelection();
		classificationBox.getSelectionModel().clearSelection();

		topicBox.getItems().clear();
		classificationBox.getItems().clear();

		unitBox.setDisable(model.getSyllabusVersion() == null);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);

		model.selectUnit(null);
		model.selectTopic(null);
		model.selectClassification(null);
	}

	/**
	 * Reloads subjects, syllabuses, and hierarchy choices after a repository change.
	 * Selections are retained when still available, including an explicitly chosen
	 * historical syllabus; unavailable selections and their dependants are cleared.
	 */
	public void refreshSubjects() {
		Subject selectedSubject = subjectBox.getValue();
		SyllabusVersion selectedSyllabus = syllabusBox.getValue();
		CurriculumNode selectedUnit = unitBox.getValue();
		CurriculumNode selectedTopic = topicBox.getValue();
		CurriculumNode selectedClassification = classificationBox.getValue();
		refreshingSubjects = true;
		try {
			subjectBox.getItems().setAll(model.getSubjects());
			selectAvailableValue(subjectBox, selectedSubject);
			model.selectSubject(subjectBox.getValue());

			syllabusBox.getItems().setAll(model.getSyllabusVersions());
			selectAvailableValue(syllabusBox, selectedSyllabus);
			model.selectSyllabusVersion(syllabusBox.getValue());
			syllabusBox.setDisable(syllabusBox.getItems().isEmpty());

			unitBox.getItems().setAll(model.getUnits());
			selectAvailableValue(unitBox, selectedUnit);
			model.selectUnit(unitBox.getValue());
			unitBox.setDisable(model.getSyllabusVersion() == null);

			topicBox.getItems().setAll(model.getTopics());
			selectAvailableValue(topicBox, selectedTopic);
			model.selectTopic(topicBox.getValue());
			topicBox.setDisable(model.getUnit() == null);

			classificationBox.getItems().setAll(model.getClassifications());
			selectAvailableValue(classificationBox, selectedClassification);
			model.selectClassification(classificationBox.getValue());
			classificationBox.setDisable(model.getTopic() == null);
		} finally {
			refreshingSubjects = false;
		}
	}

	private <T> void selectAvailableValue(ComboBox<T> box, T selectedValue) {
		for (T item : box.getItems()) {
			if (item.equals(selectedValue)) {
				box.setValue(item);
				return;
			}
		}
		box.setValue(null);
	}

	/**
	 * Exposes the subject selected by this pane.
	 *
	 * @return the read-only selected-subject property
	 */
	public ReadOnlyObjectProperty<Subject> selectedSubjectProperty() {
		return subjectBox.valueProperty();
	}

	private void handleSubjectSelection() {
		if (refreshingSubjects) {
			return;
		}
		Subject subject = subjectBox.getValue();
		syllabusBox.getSelectionModel().clearSelection();
		syllabusBox.getItems().clear();
		unitBox.getSelectionModel().clearSelection();
		topicBox.getSelectionModel().clearSelection();
		classificationBox.getSelectionModel().clearSelection();
		unitBox.getItems().clear();
		topicBox.getItems().clear();
		classificationBox.getItems().clear();
		syllabusBox.setDisable(true);
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		model.selectSubject(subject);
		if (subject == null) {
			return;
		}
		syllabusBox.getItems().setAll(model.getSyllabusVersions());
		syllabusBox.setDisable(syllabusBox.getItems().isEmpty());
		SyllabusVersion defaultVersion = model.getSyllabusVersion();
		if (defaultVersion != null) {
			syllabusBox.setValue(defaultVersion);
		}
	}

	private void handleSyllabusSelection() {
		if (refreshingSubjects) {
			return;
		}
		SyllabusVersion syllabusVersion = syllabusBox.getValue();
		unitBox.getSelectionModel().clearSelection();
		topicBox.getSelectionModel().clearSelection();
		classificationBox.getSelectionModel().clearSelection();
		unitBox.getItems().clear();
		topicBox.getItems().clear();
		classificationBox.getItems().clear();
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		model.selectSyllabusVersion(syllabusVersion);
		if (syllabusVersion == null) {
			return;
		}
		unitBox.getItems().setAll(model.getUnits());
		unitBox.setDisable(false);
	}

	private void handleUnitSelection() {
		if (refreshingSubjects) {
			return;
		}
		CurriculumNode unit = unitBox.getValue();

		model.selectUnit(unit);

		topicBox.getSelectionModel().clearSelection();
		classificationBox.getSelectionModel().clearSelection();
		classificationBox.getItems().clear();

		if (unit == null) {
			topicBox.getItems().clear();
			topicBox.setDisable(true);
			classificationBox.setDisable(true);
			return;
		}

		topicBox.getItems().setAll(model.getTopics());
		topicBox.setDisable(false);
		classificationBox.setDisable(true);
	}

	private void handleTopicSelection() {
		if (refreshingSubjects) {
			return;
		}
		CurriculumNode topic = topicBox.getValue();

		model.selectTopic(topic);

		classificationBox.getSelectionModel().clearSelection();

		if (topic == null) {
			classificationBox.getItems().clear();
			classificationBox.setDisable(true);
			return;
		}

		classificationBox.getItems().setAll(model.getClassifications());
		classificationBox.setDisable(false);
	}

	private void handleClassificationSelection() {
		if (refreshingSubjects) {
			return;
		}
		model.selectClassification(classificationBox.getValue());
	}

	private void configureSelectionHandlers() {

		subjectBox.setOnAction(event -> handleSubjectSelection());
		syllabusBox.setOnAction(event -> handleSyllabusSelection());
		unitBox.setOnAction(event -> handleUnitSelection());
		topicBox.setOnAction(event -> handleTopicSelection());
		classificationBox.setOnAction(event -> handleClassificationSelection());
	}
}
