package au.edu.eq.questionbank.ui;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * JavaFX controls for selecting a subject and one best-fit curriculum subtopic
 * through its unit/topic hierarchy.
 */
public class CurriculumSelectorPane extends VBox {

	private final CurriculumSelectionModel model;

	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> subtopicBox = new ComboBox<>();

	/**
	 * Creates a selector bound to the supplied selection model.
	 *
	 * @param model the curriculum selection state and lookup model
	 */
	public CurriculumSelectorPane(CurriculumSelectionModel model) {
		this.model = model;

		setSpacing(6);
		setPadding(new Insets(10));

		subjectBox.setPromptText("Select subject");
		unitBox.setPromptText("Select unit");
		topicBox.setPromptText("Select topic");
		subtopicBox.setPromptText("Select subtopic");

		subjectBox.setMaxWidth(Double.MAX_VALUE);
		unitBox.setMaxWidth(Double.MAX_VALUE);
		topicBox.setMaxWidth(Double.MAX_VALUE);
		subtopicBox.setMaxWidth(Double.MAX_VALUE);

		unitBox.setDisable(true);
		topicBox.setDisable(true);
		subtopicBox.setDisable(true);

		configureSelectionHandlers();

		subjectBox.getItems().setAll(model.getSubjects());

		getChildren().addAll(new Label("Classification"), new Label("Subject"), subjectBox, new Label("Unit"), unitBox,
				new Label("Topic"), topicBox, new Label("Subtopic"), subtopicBox);
	}

	private void configureSelectionHandlers() {

		subjectBox.setOnAction(event -> {
			Subject subject = subjectBox.getValue();

			model.selectSubject(subject);

			unitBox.getSelectionModel().clearSelection();
			topicBox.getSelectionModel().clearSelection();
			subtopicBox.getSelectionModel().clearSelection();

			topicBox.getItems().clear();
			subtopicBox.getItems().clear();

			if (subject == null) {
				unitBox.getItems().clear();
				unitBox.setDisable(true);
				topicBox.setDisable(true);
				subtopicBox.setDisable(true);
				return;
			}

			unitBox.getItems().setAll(model.getUnits());
			unitBox.setDisable(false);
			topicBox.setDisable(true);
			subtopicBox.setDisable(true);
		});

		unitBox.setOnAction(event -> {
			CurriculumNode unit = unitBox.getValue();

			model.selectUnit(unit);

			topicBox.getSelectionModel().clearSelection();
			subtopicBox.getSelectionModel().clearSelection();
			subtopicBox.getItems().clear();

			if (unit == null) {
				topicBox.getItems().clear();
				topicBox.setDisable(true);
				subtopicBox.setDisable(true);
				return;
			}

			topicBox.getItems().setAll(model.getTopics());
			topicBox.setDisable(false);
			subtopicBox.setDisable(true);
		});

		topicBox.setOnAction(event -> {
			CurriculumNode topic = topicBox.getValue();

			model.selectTopic(topic);

			subtopicBox.getSelectionModel().clearSelection();

			if (topic == null) {
				subtopicBox.getItems().clear();
				subtopicBox.setDisable(true);
				return;
			}

			subtopicBox.getItems().setAll(model.getSubtopics());
			subtopicBox.setDisable(false);
		});

		subtopicBox.setOnAction(event -> {
			model.selectSubtopic(subtopicBox.getValue());
		});
	}

}
