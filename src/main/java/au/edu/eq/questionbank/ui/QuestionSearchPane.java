package au.edu.eq.questionbank.ui;

import java.util.List;
import java.util.StringJoiner;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class QuestionSearchPane extends BorderPane {

	private final CurriculumRepository curriculumRepository;
	private final QuestionRetrievalService retrievalService;
	private final ComboBox<Subject> subjectBox = new ComboBox<>();
	private final Label syllabusValue = new Label();
	private final ComboBox<CurriculumNode> unitBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> topicBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();
	private final ComboBox<CurriculumNode> descriptorBox = new ComboBox<>();
	private final Label statusLabel = new Label();
	private final ListView<QuestionRetrievalResult> resultsList = new ListView<>();
	private final TextArea detailsArea = new TextArea();
	private SyllabusVersion currentSyllabus;
	private Task<List<QuestionRetrievalResult>> activeSearchTask;
	private long searchGeneration;
	private boolean updatingControls;

	public QuestionSearchPane(CurriculumRepository curriculumRepository, QuestionRetrievalService retrievalService) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (retrievalService == null) {
			throw new NullPointerException("retrievalService");
		}
		this.curriculumRepository = curriculumRepository;
		this.retrievalService = retrievalService;
		setPadding(new Insets(10));
		configureControls();
		configureHandlers();
		setTop(createSelectionPane());
		setCenter(resultsList);
		setBottom(createDetailsPane());
		subjectBox.getItems().setAll(curriculumRepository.findAllSubjects());
	}

	private void cancelActiveSearch() {
		searchGeneration++;
		if (activeSearchTask != null) {
			activeSearchTask.cancel();
			activeSearchTask = null;
		}
	}

	private void classificationBoxMousePress() {
	}

	private void clearBelowSubject() {
		updatingControls = true;
		try {
			currentSyllabus = null;
			syllabusValue.setText("No current syllabus");
			resetUnitBox();
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
		cancelActiveSearch();
		clearResults();
		statusLabel.setText("");
	}

	private void clearBelowTopic() {
		updatingControls = true;
		try {
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
	}

	private void clearResults() {
		resultsList.getItems().clear();
		detailsArea.clear();
	}

	private void configureControls() {
		subjectBox.setId("question-search-subject");
		unitBox.setId("question-search-unit");
		topicBox.setId("question-search-topic");
		classificationBox.setId("question-search-classification");
		descriptorBox.setId("question-search-descriptor");
		resultsList.setId("question-search-results");
		detailsArea.setId("question-search-details");
		statusLabel.setId("question-search-status");
		subjectBox.setPromptText("Select subject");
		unitBox.setPromptText("Select unit");
		topicBox.setPromptText("Select topic");
		classificationBox.setPromptText("Select subtopic or descriptor");
		descriptorBox.setPromptText("Select descriptor");
		configurePromptDisplay(subjectBox);
		configurePromptDisplay(unitBox);
		configurePromptDisplay(topicBox);
		configurePromptDisplay(classificationBox);
		configurePromptDisplay(descriptorBox);
		syllabusValue.setText("No current syllabus");
		unitBox.setDisable(true);
		topicBox.setDisable(true);
		classificationBox.setDisable(true);
		descriptorBox.setDisable(true);
		subjectBox.setMaxWidth(Double.MAX_VALUE);
		unitBox.setMaxWidth(Double.MAX_VALUE);
		topicBox.setMaxWidth(Double.MAX_VALUE);
		classificationBox.setMaxWidth(Double.MAX_VALUE);
		descriptorBox.setMaxWidth(Double.MAX_VALUE);
		detailsArea.setEditable(false);
		detailsArea.setWrapText(true);
		detailsArea.setPrefRowCount(6);
		resultsList.setCellFactory(listView -> new ListCell<>() {

			@Override
			protected void updateItem(QuestionRetrievalResult result, boolean empty) {
				super.updateItem(result, empty);
				if (empty || result == null) {
					setText(null);
					return;
				}
				Question question = result.getQuestion();
				setText(question.getExam().getProvider().getName() + " " + question.getExam().getYear() + " — "
						+ question.getBooklet().getName() + " — " + question.getQuestionCode() + " — "
						+ question.getMarks() + " marks");
			}
		});
	}

	private void configureHandlers() {
		subjectBox.setOnAction(event -> handleSubjectSelection());
		unitBox.setOnAction(event -> handleUnitSelection());
		topicBox.setOnAction(event -> handleTopicSelection());
		classificationBox.setOnAction(event -> handleClassificationSelection());
		descriptorBox.setOnAction(event -> handleDescriptorSelection());
		subjectBox.setOnMousePressed(event -> handleSubjectBoxMousePressed());
		unitBox.setOnMousePressed(event -> handleUnitBoxMousePress());
		topicBox.setOnMousePressed(event -> handleTopicBoxMousePress());
		classificationBox.setOnMousePressed(event -> handleClassificationBoxMousePress());
		descriptorBox.setOnMousePressed(event -> handleDescriptorBoxMousePress());
		resultsList.getSelectionModel().selectedItemProperty()
				.addListener((observable, oldResult, newResult) -> showResultDetails(newResult));
	}

	private <T> void configurePromptDisplay(ComboBox<T> comboBox) {
		comboBox.setButtonCell(new ListCell<>() {

			@Override
			protected void updateItem(T item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(comboBox.getPromptText());
					return;
				}
				setText(item.toString());
			}
		});
	}

	private VBox createDetailsPane() {
		Label label = new Label("Question details");
		label.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(4, label, detailsArea);
		pane.setPadding(new Insets(10, 0, 0, 0));
		return pane;
	}

	private GridPane createSelectionPane() {
		GridPane pane = new GridPane();
		pane.setHgap(8);
		pane.setVgap(6);
		pane.setPadding(new Insets(0, 0, 10, 0));
		pane.add(new Label("Subject"), 0, 0);
		pane.add(subjectBox, 1, 0);
		pane.add(new Label("Current syllabus"), 0, 1);
		pane.add(syllabusValue, 1, 1);
		pane.add(new Label("Unit"), 0, 2);
		pane.add(unitBox, 1, 2);
		pane.add(new Label("Topic"), 0, 3);
		pane.add(topicBox, 1, 3);
		pane.add(new Label("Subtopic / Descriptor"), 0, 4);
		pane.add(classificationBox, 1, 4);
		pane.add(new Label("Descriptor"), 0, 5);
		pane.add(descriptorBox, 1, 5);
		pane.add(statusLabel, 1, 6);
		GridPane.setHgrow(subjectBox, Priority.ALWAYS);
		GridPane.setHgrow(unitBox, Priority.ALWAYS);
		GridPane.setHgrow(topicBox, Priority.ALWAYS);
		GridPane.setHgrow(classificationBox, Priority.ALWAYS);
		GridPane.setHgrow(descriptorBox, Priority.ALWAYS);
		return pane;
	}

	private CurriculumNode getSearchNode() {
		CurriculumNode descriptor = descriptorBox.getValue();
		if (descriptor != null) {
			return descriptor;
		}
		CurriculumNode classification = classificationBox.getValue();
		if (classification != null) {
			return classification;
		}
		CurriculumNode topic = topicBox.getValue();
		if (topic != null) {
			return topic;
		}
		return unitBox.getValue();
	}

	private void handleClassificationBoxMousePress() {
		if (!updatingControls && classificationBox.getValue() != null) {
			Platform.runLater(() -> handleClassificationSelection());
		}
	}

	private void handleClassificationSelection() {
		if (updatingControls) {
			return;
		}
		CurriculumNode classification = classificationBox.getValue();
		updatingControls = true;
		try {
			resetDescriptorBox();
			if (classification != null && classification.getLevel() == CurriculumLevel.SUBTOPIC) {
				List<CurriculumNode> children = curriculumRepository.findChildren(classification);
				descriptorBox.getItems().setAll(children);
				descriptorBox.setDisable(children.isEmpty());
			}
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(classification);
	}

	private void handleDescriptorBoxMousePress() {
		if (!updatingControls && descriptorBox.getValue() != null) {
			Platform.runLater(() -> handleDescriptorSelection());
		}
	}

	private void handleDescriptorSelection() {
		if (updatingControls) {
			return;
		}
		startAutomaticSearch(descriptorBox.getValue());
	}

	private void handleSubjectBoxMousePressed() {
		if (!updatingControls && subjectBox.getValue() != null) {
			Platform.runLater(() -> handleSubjectSelection());
		}
	}

	private void handleSubjectSelection() {
		if (updatingControls) {
			return;
		}
		clearBelowSubject();
		Subject subject = subjectBox.getValue();
		if (subject == null) {
			return;
		}
		for (SyllabusVersion version : curriculumRepository.findVersionsForSubject(subject)) {
			if (version.isCurrent()) {
				currentSyllabus = version;
				break;
			}
		}
		if (currentSyllabus == null) {
			statusLabel.setText("No current syllabus available.");
			return;
		}
		syllabusValue.setText(currentSyllabus.getName());
		List<CurriculumNode> units = curriculumRepository.findRootNodes(currentSyllabus);
		updatingControls = true;
		try {
			unitBox.getItems().setAll(units);
			unitBox.getSelectionModel().clearSelection();
			unitBox.setValue(null);
			unitBox.setDisable(units.isEmpty());
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(subject);
	}

	private void handleTopicBoxMousePress() {
		if (!updatingControls && topicBox.getValue() != null) {
			Platform.runLater(() -> handleTopicSelection());
		}
	}

	private void handleTopicSelection() {
		if (updatingControls) {
			return;
		}
		clearBelowTopic();
		CurriculumNode topic = topicBox.getValue();
		if (topic != null) {
			List<CurriculumNode> classifications = curriculumRepository.findChildren(topic);
			updatingControls = true;
			try {
				classificationBox.getItems().setAll(classifications);
				classificationBox.setDisable(classifications.isEmpty());
			} finally {
				updatingControls = false;
			}
		}
		startAutomaticSearch(topic);
	}

	private void handleUnitBoxMousePress() {
		if (!updatingControls && unitBox.getValue() != null) {
			Platform.runLater(() -> handleUnitSelection());
		}
	}

	private void handleUnitSelection() {
		if (updatingControls) {
			return;
		}
		CurriculumNode unit = unitBox.getValue();
		updatingControls = true;
		try {
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
			if (unit != null) {
				List<CurriculumNode> topics = curriculumRepository.findChildren(unit);
				topicBox.getItems().setAll(topics);
				topicBox.setDisable(topics.isEmpty());
			}
		} finally {
			updatingControls = false;
		}
		startAutomaticSearch(unit);
	}

	private String nodeDescription(CurriculumNode node) {
		return node.getSyllabusVersion().getName() + " " + node.getCode() + " " + node.getName();
	}

	private void resetClassificationBox() {
		classificationBox.getSelectionModel().clearSelection();
		classificationBox.setValue(null);
		classificationBox.getItems().clear();
		classificationBox.setPromptText("Select subtopic or descriptor");
		classificationBox.setDisable(true);
	}

	private void resetDescriptorBox() {
		descriptorBox.getSelectionModel().clearSelection();
		descriptorBox.setValue(null);
		descriptorBox.getItems().clear();
		descriptorBox.setPromptText("Select descriptor");
		descriptorBox.setDisable(true);
	}

	private void resetTopicBox() {
		topicBox.getSelectionModel().clearSelection();
		topicBox.setValue(null);
		topicBox.getItems().clear();
		topicBox.setPromptText("Select topic");
		topicBox.setDisable(true);
	}

	private void resetUnitBox() {
		unitBox.getSelectionModel().clearSelection();
		unitBox.setValue(null);
		unitBox.getItems().clear();
		unitBox.setPromptText("Select unit");
		unitBox.setDisable(true);
	}

	private void showResultDetails(QuestionRetrievalResult result) {
		if (result == null) {
			detailsArea.clear();
			return;
		}
		Question question = result.getQuestion();
		StringJoiner applicability = new StringJoiner(System.lineSeparator());
		for (CurriculumNode node : result.getCurrentApplicability()) {
			applicability.add(nodeDescription(node));
		}
		String questionText = question.getQuestionText();
		if (questionText.isBlank()) {
			questionText = "No transcribed question text.";
		}
		detailsArea.setText("""
				Question: %s
				Marks: %d

				Original classification:
				%s

				Current applicability:
				%s

				Question text:
				%s
				""".formatted(question.getQuestionCode(), question.getMarks(),
				nodeDescription(result.getOriginalClassification()), applicability, questionText));
	}

	private void startAutomaticSearch(CurriculumNode currentNode) {
		if (currentNode == null) {
			cancelActiveSearch();
			clearResults();
			statusLabel.setText("");
			return;
		}
		startAutomaticSearch(() -> retrievalService.findQuestionsApplicableTo(currentNode));
	}

	private void startAutomaticSearch(Subject subject) {
		if (subject == null) {
			cancelActiveSearch();
			clearResults();
			statusLabel.setText("");
			return;
		}
		startAutomaticSearch(() -> retrievalService.findQuestionsApplicableTo(subject));
	}

	private void startAutomaticSearch(Supplier<List<QuestionRetrievalResult>> retrieval) {
		cancelActiveSearch();
		clearResults();
		long generation = searchGeneration;
		statusLabel.setText("Searching...");
		Task<List<QuestionRetrievalResult>> task = new Task<>() {

			@Override
			protected List<QuestionRetrievalResult> call() {
				return retrieval.get();
			}
		};
		activeSearchTask = task;
		task.setOnSucceeded(event -> {
			if (generation != searchGeneration || task != activeSearchTask) {
				return;
			}
			activeSearchTask = null;
			List<QuestionRetrievalResult> results = task.getValue();
			resultsList.getItems().setAll(results);
			if (results.isEmpty()) {
				statusLabel.setText("No questions found.");
			} else if (results.size() == 1) {
				statusLabel.setText("1 question found.");
			} else {
				statusLabel.setText(results.size() + " questions found.");
			}
		});
		task.setOnFailed(event -> {
			if (generation != searchGeneration || task != activeSearchTask) {
				return;
			}
			activeSearchTask = null;
			Throwable failure = task.getException();
			if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
				statusLabel.setText("Question search failed.");
			} else {
				statusLabel.setText("Question search failed: " + failure.getMessage());
			}
		});
		Thread.ofVirtual().name("question-search").start(task);
	}
}