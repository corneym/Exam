package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Curriculum-aware question search pane.
 * <p>
 * Searches automatically against the selected current-curriculum scope from
 * Subject through Unit, Topic, Subtopic and Descriptor. Retrieval runs away
 * from the JavaFX application thread and stale results are discarded when the
 * user changes search scope.
 */
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
	private final QuestionPreviewService previewService;
	private final ImageView previewImageView = new ImageView();
	private final Label previewStatusLabel = new Label();
	private Task<Optional<BufferedImage>> activePreviewTask;
	private long previewGeneration;
	private Task<?> activeHierarchyTask;
	private long hierarchyGeneration;
	private boolean disposed;

	/**
	 * Creates the question-search pane.
	 *
	 * @param curriculumRepository current curriculum hierarchy lookup
	 * @param retrievalService     curriculum-aware question retrieval
	 * @param previewService       stored question image preview service
	 * @throws NullPointerException if any dependency is {@code null}
	 */
	public QuestionSearchPane(CurriculumRepository curriculumRepository, QuestionRetrievalService retrievalService,
			QuestionPreviewService previewService) {
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		if (retrievalService == null) {
			throw new NullPointerException("retrievalService");
		}
		if (previewService == null) {
			throw new NullPointerException("previewService");
		}
		this.curriculumRepository = curriculumRepository;
		this.retrievalService = retrievalService;
		this.previewService = previewService;
		setPadding(new Insets(10));
		configureControls();
		configureHandlers();
		setTop(createSelectionPane());
		setCenter(createResultsAndDetailsPane());
		startSubjectLoading();
	}

	void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;
		cancelActiveHierarchyLoad();
		cancelActiveSearch();
		cancelActivePreview();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearPreview();
		statusLabel.setText("");
	}

	private void cancelActiveHierarchyLoad() {
		hierarchyGeneration++;
		if (activeHierarchyTask != null) {
			activeHierarchyTask.cancel();
			activeHierarchyTask = null;
		}
	}

	private void cancelActivePreview() {
		previewGeneration++;
		if (activePreviewTask != null) {
			activePreviewTask.cancel();
			activePreviewTask = null;
		}
	}

	private void cancelActiveSearch() {
		searchGeneration++;
		if (activeSearchTask != null) {
			activeSearchTask.cancel();
			activeSearchTask = null;
		}
	}

	private void clearBelowSubject() {
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
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

	private void clearPreview() {
		previewImageView.setImage(null);
		previewStatusLabel.setText("");
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
		previewImageView.setId("question-search-preview");
		previewStatusLabel.setId("question-search-preview-status");
		previewImageView.setPreserveRatio(true);
		previewImageView.setFitWidth(820);
		previewImageView.setSmooth(true);
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
		subjectBox.setOnMousePressed(event -> handleSubjectBoxMousePress());
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

	private VBox createQuestionDetailsPane() {
		Label label = new Label("Question details");
		label.setStyle("-fx-font-weight: bold;");
		VBox pane = new VBox(4, label, detailsArea);
		pane.setPadding(new Insets(6, 0, 0, 0));
		VBox.setVgrow(detailsArea, Priority.ALWAYS);
		return pane;
	}

	private VBox createQuestionPreviewPane() {
		Label label = new Label("Question preview");
		label.setStyle("-fx-font-weight: bold;");
		ScrollPane previewPane = new ScrollPane(previewImageView);
		previewPane.setFitToWidth(true);
		VBox pane = new VBox(4, label, previewStatusLabel, previewPane);
		pane.setPadding(new Insets(6, 0, 0, 0));
		VBox.setVgrow(previewPane, Priority.ALWAYS);
		return pane;
	}

	private SplitPane createResultsAndDetailsPane() {
		Label resultsLabel = new Label("Matching questions");
		resultsLabel.setStyle("-fx-font-weight: bold;");
		VBox resultsPane = new VBox(4, resultsLabel, resultsList);
		VBox.setVgrow(resultsList, Priority.ALWAYS);
		VBox detailsPane = createQuestionDetailsPane();
		VBox previewPane = createQuestionPreviewPane();
		SplitPane pane = new SplitPane(resultsPane, detailsPane, previewPane);
		pane.setOrientation(Orientation.VERTICAL);
		pane.setDividerPositions(0.22, 0.48);
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

	private void handleClassificationBoxMousePress() {
		if (!updatingControls && classificationBox.getValue() != null) {
			Platform.runLater(() -> handleClassificationSelection());
		}
	}

	private void handleClassificationSelection() {
		if (updatingControls) {
			return;
		}
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		CurriculumNode classification = classificationBox.getValue();
		updatingControls = true;
		try {
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
		if (classification == null) {
			return;
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC) {
			startAutomaticSearch(classification);
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(classification), children -> {
			updatingControls = true;
			try {
				descriptorBox.getItems().setAll(children);
				descriptorBox.setDisable(children.isEmpty());
			} finally {
				updatingControls = false;
			}
			startAutomaticSearch(classification);
		});
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
		cancelActiveHierarchyLoad();
		startAutomaticSearch(descriptorBox.getValue());
	}

	private void handleSubjectBoxMousePress() {
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
		startHierarchyLoad(() -> loadSubjectNavigation(subject), navigation -> {
			currentSyllabus = navigation.currentSyllabus();
			if (currentSyllabus == null) {
				statusLabel.setText("No current syllabus available.");
				return;
			}
			syllabusValue.setText(currentSyllabus.getName());
			updatingControls = true;
			try {
				unitBox.getItems().setAll(navigation.units());
				unitBox.getSelectionModel().clearSelection();
				unitBox.setValue(null);
				unitBox.setDisable(navigation.units().isEmpty());
			} finally {
				updatingControls = false;
			}
			startAutomaticSearch(subject);
		});
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
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		clearBelowTopic();
		CurriculumNode topic = topicBox.getValue();
		if (topic == null) {
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(topic), classifications -> {
			updatingControls = true;
			try {
				classificationBox.getItems().setAll(classifications);
				classificationBox.setDisable(classifications.isEmpty());
			} finally {
				updatingControls = false;
			}
			startAutomaticSearch(topic);
		});
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
		cancelActiveHierarchyLoad();
		invalidateCurrentSearch();
		CurriculumNode unit = unitBox.getValue();
		updatingControls = true;
		try {
			resetTopicBox();
			resetClassificationBox();
			resetDescriptorBox();
		} finally {
			updatingControls = false;
		}
		if (unit == null) {
			return;
		}
		startHierarchyLoad(() -> curriculumRepository.findChildren(unit), topics -> {
			updatingControls = true;
			try {
				topicBox.getItems().setAll(topics);
				topicBox.setDisable(topics.isEmpty());
			} finally {
				updatingControls = false;
			}
			startAutomaticSearch(unit);
		});
	}

	private void invalidateCurrentSearch() {
		cancelActiveSearch();
		cancelActivePreview();
		resultsList.getItems().clear();
		detailsArea.clear();
		clearPreview();
		statusLabel.setText("");
	}

	private SubjectNavigation loadSubjectNavigation(Subject subject) {
		List<SyllabusVersion> versions = curriculumRepository.findVersionsForSubject(subject);
		SyllabusVersion currentVersion = null;
		for (SyllabusVersion version : versions) {
			if (!version.isCurrent()) {
				continue;
			}
			if (currentVersion != null) {
				throw new IllegalStateException("Subject has multiple current syllabus versions");
			}
			currentVersion = version;
		}
		if (currentVersion == null) {
			return new SubjectNavigation(null, List.of());
		}
		List<CurriculumNode> units = curriculumRepository.findRootNodes(currentVersion);
		return new SubjectNavigation(currentVersion, units);
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
		if (disposed) {
			return;
		}
		cancelActivePreview();
		clearPreview();
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
		startQuestionPreview(question);
	}

	private void startAutomaticSearch(CurriculumNode currentNode) {
		if (disposed) {
			return;
		}
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

	private <T> void startHierarchyLoad(Supplier<T> loader, Consumer<T> onSucceeded) {
		if (disposed) {
			return;
		}
		cancelActiveHierarchyLoad();
		long generation = hierarchyGeneration;
		statusLabel.setText("Loading curriculum...");
		Task<T> task = new Task<>() {

			@Override
			protected T call() {
				return loader.get();
			}
		};
		activeHierarchyTask = task;
		task.setOnSucceeded(event -> {
			if (generation != hierarchyGeneration || task != activeHierarchyTask) {
				return;
			}
			activeHierarchyTask = null;
			statusLabel.setText("");
			onSucceeded.accept(task.getValue());
		});
		task.setOnFailed(event -> {
			if (generation != hierarchyGeneration || task != activeHierarchyTask) {
				return;
			}
			activeHierarchyTask = null;
			invalidateCurrentSearch();
			Throwable failure = task.getException();
			if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
				statusLabel.setText("Curriculum navigation failed.");
			} else {
				statusLabel.setText("Curriculum navigation failed: " + failure.getMessage());
			}
		});
		Thread.ofVirtual().name("curriculum-navigation").start(task);
	}

	private void startQuestionPreview(Question question) {
		if (disposed) {
			return;
		}
		if (question.getRegions().isEmpty()) {
			previewStatusLabel.setText("No stored question image.");
			return;
		}
		long generation = previewGeneration;
		previewStatusLabel.setText("Loading preview...");
		Task<Optional<BufferedImage>> task = new Task<>() {

			@Override
			protected Optional<BufferedImage> call() throws Exception {
				return previewService.loadPreview(question);
			}
		};
		activePreviewTask = task;
		task.setOnSucceeded(event -> {
			if (generation != previewGeneration || task != activePreviewTask) {
				return;
			}
			activePreviewTask = null;
			Optional<BufferedImage> preview = task.getValue();
			if (preview.isEmpty()) {
				previewStatusLabel.setText("No stored question image.");
				return;
			}
			Image image = SwingFXUtils.toFXImage(preview.get(), null);
			previewImageView.setImage(image);
			previewStatusLabel.setText("");
		});
		task.setOnFailed(event -> {
			if (generation != previewGeneration || task != activePreviewTask) {
				return;
			}
			activePreviewTask = null;
			Throwable failure = task.getException();
			if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
				previewStatusLabel.setText("Question preview unavailable.");
			} else {
				previewStatusLabel.setText("Question preview unavailable: " + failure.getMessage());
			}
		});
		Thread.ofVirtual().name("question-preview").start(task);
	}

	private void startSubjectLoading() {
		startHierarchyLoad(() -> curriculumRepository.findAllSubjects(),
				subjects -> subjectBox.getItems().setAll(subjects));
	}

	private record SubjectNavigation(SyllabusVersion currentSyllabus, List<CurriculumNode> units) {
	}
}