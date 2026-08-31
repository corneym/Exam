package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * JavaFX controls for navigating the current syllabus of a subject through its
 * unit and topic hierarchy and selecting the final classification shown by the
 * model.
 */
public class CurriculumSelectorPane extends VBox {

	private final CurriculumSelectionModel model;

	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();

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

		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);

		configureSelectionHandlers();
		refreshSubjects();
		getChildren().addAll(classificationLabel, new Label("Subject"), subjectBox, new Label("Unit"), unitBox,
				new Label("Topic"), topicBox, new Label("Subtopic / Descriptor"), classificationBox);
	}

	/**
	 * Clears unit, topic, and final-classification state while retaining the
	 * selected subject.
	 */
	public void clearClassificationBelowSubject() {
		unitBox.getSelectionModel().clearSelection();
		topicBox.getSelectionModel().clearSelection();
		classificationBox.getSelectionModel().clearSelection();

		topicBox.getItems().clear();
		classificationBox.getItems().clear();

		unitBox.setDisable(false);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);

		model.selectUnit(null);
		model.selectTopic(null);
		model.selectClassification(null);
	}

	/**
	 * Reloads available subjects while preserving the current selection when it
	 * still exists.
	 */
	public void refreshSubjects() {
		Subject selectedSubject = subjectBox.getValue();
		subjectBox.getItems().setAll(model.getSubjects());
		if (selectedSubject != null && subjectBox.getItems().contains(selectedSubject)) {
			subjectBox.setValue(selectedSubject);
		}
	}

	/**
	 * Exposes the subject selected by this pane.
	 *
	 * @return the read-only selected-subject property
	 */
	public ReadOnlyObjectProperty<Subject> selectedSubjectProperty() {
		return subjectBox.valueProperty();
	}

	private void configureSelectionHandlers() {

		subjectBox.setOnAction(event -> {
			Subject subject = subjectBox.getValue();
			unitBox.getSelectionModel().clearSelection();
			topicBox.getSelectionModel().clearSelection();
			classificationBox.getSelectionModel().clearSelection();
			unitBox.getItems().clear();
			topicBox.getItems().clear();
			classificationBox.getItems().clear();
			unitBox.setDisable(true);
			topicBox.setDisable(true);
			classificationBox.setDisable(true);
			if (subject == null) {
				model.selectSubject(null);
				return;
			}
			try {
				model.selectSubject(subject);
				unitBox.getItems().setAll(model.getUnits());
				unitBox.setDisable(false);
			} catch (IllegalStateException e) {
				/*
				 * The subject exists, but it has no current syllabus version. It cannot
				 * currently be used for question classification.
				 */
				subjectBox.getSelectionModel().clearSelection();
			}
		});

		unitBox.setOnAction(event -> {
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
		});

		topicBox.setOnAction(event -> {
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
		});

		classificationBox.setOnAction(event -> {
			model.selectClassification(classificationBox.getValue());
		});
	}
}
