package au.edu.eq.questionbank.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.PreambleStatus;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.SourceQuestionCodeParser;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Owns the question-capture controls, pending regions, previews, validation,
 * and save workflow for new, imported and edited questions. Shared preambles
 * are captured separately from ordinary question regions. All control and
 * capture-state access belongs on the JavaFX application thread.
 */
final class QuestionCapturePane extends VBox {

	private static final double COMPACT_SPACING = 4.0;
	private static final double CONTROL_SPACING = 8.0;
	private static final double REGION_PREVIEW_ITEM_SPACING = 5.0;
	private static final double SECTION_SPACING = 10.0;
	private static final double MARKS_FIELD_WIDTH = 60.0;
	private static final double QUESTION_CODE_FIELD_WIDTH = 100.0;
	private static final double REGIONS_VIEWPORT_HEIGHT = 300.0;
	private static final Insets COMPACT_BUTTON_PADDING = new Insets(2, 8, 2, 8);
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String REQUIRED_STATUS_STYLE = "-fx-text-fill: #b71c1c;-fx-font-weight: bold;";
	private static final String SECTION_HEADING_STYLE = "-fx-font-weight: bold;";
	private static final String SUCCESS_STATUS_STYLE = "-fx-text-fill: #2e7d32;";
	// Workflow dependencies and application callbacks.
	private final QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final CurriculumSelectorPane curriculumSelectorPane;
	private final Supplier<ExamBooklet> bookletSupplier;
	private final Supplier<PdfSession> examPdfSessionSupplier;
	private final Runnable selectionClearHandler;
	private final Runnable questionsChangedHandler;
	private final Predicate<Question> importedQuestionActivationHandler;
	private final BooleanSupplier questionTargetChangeAllowed;
	private final BooleanSupplier questionSelectionTransferHandler;
	private final SharedContextCapturePane sharedContextCapturePane;
	private final SourceQuestionRepository sourceQuestionRepository;
	private final SqliteQuestionCaptureService questionCaptureService;
	// Capture mode and imported-question selection.
	private final ToggleButton newQuestionsModeButton = new ToggleButton("New Questions");
	private final ToggleButton importedQuestionsModeButton = new ToggleButton("Imported Questions");
	private final ToggleGroup captureModeGroup = new ToggleGroup();
	private final ComboBox<Question> importedQuestionBox = new ComboBox<>();
	private final Label importedClassificationLabel = new Label();
	private final Label captureHintLabel = new Label();
	private final VBox legacyCaptureBox = new VBox(COMPACT_SPACING);
	// Question metadata and shared preamble controls.
	private final TextField questionCodeField = new TextField();
	private final TextField marksField = new TextField();
	private final CheckBox firstRegionPreambleCheckBox = new CheckBox("First region is shared preamble");
	private final Label preambleStatusLabel = new Label();
	private final VBox preambleControlsBox = new VBox(COMPACT_SPACING);
	// Pending and accepted region controls.
	private final Button addRegionButton = new Button("Add Region");
	private final Button removeCurrentSelectionButton = new Button("Clear");
	private final Button clearRegionsButton = new Button("Clear Regions");
	private final Label regionCountLabel = new Label("Regions: 0");
	private final VBox regionPreviewBox = new VBox(SECTION_SPACING);
	private final ScrollPane regionsScrollPane = new ScrollPane(regionPreviewBox);
	// Save and edit controls.
	private final Button saveQuestionButton = new Button("Save Question");
	private final Button cancelQuestionEditButton = new Button("Cancel");
	private final Label saveStatusLabel = new Label();
	// Transient capture and edit state.
	private boolean refreshingPreambleControls;
	private boolean questionSaveInProgress;
	private Question importedQuestion;
	private boolean refreshingImportedQuestions;
	private boolean importedCaptureMode;
	private QuestionRegion currentSelection;
	private final List<QuestionRegion> pendingRegions = new ArrayList<>();
	private Question editingQuestion;
	private boolean loadingQuestionEdit;
	private Runnable questionEditCompletedHandler = () -> {
	};

	/**
	 * Creates the question-capture workflow and its repository integration.
	 * Suppliers provide the active booklet and PDF session, while the callbacks
	 * coordinate selection ownership, imported-question activation and downstream
	 * question refreshes with the containing application.
	 */
	QuestionCapturePane(QuestionRepository questionRepository, SourceQuestionRepository sourceQuestionRepository,
			SqliteQuestionCaptureService questionCaptureService, SharedContextCapturePane sharedContextCapturePane,
			QuestionExtractor questionExtractor, CurriculumSelectionModel curriculumSelectionModel,
			CurriculumSelectorPane curriculumSelectorPane, Supplier<ExamBooklet> bookletSupplier,
			Supplier<PdfSession> examPdfSessionSupplier, Predicate<Question> importedQuestionActivationHandler,
			BooleanSupplier questionTargetChangeAllowed, BooleanSupplier questionSelectionTransferHandler,
			Runnable selectionClearHandler, Runnable questionsChangedHandler) {
		validateDependencies(questionRepository, sourceQuestionRepository, questionCaptureService,
				sharedContextCapturePane, questionExtractor, curriculumSelectionModel, curriculumSelectorPane,
				bookletSupplier, examPdfSessionSupplier, importedQuestionActivationHandler, questionTargetChangeAllowed,
				questionSelectionTransferHandler, selectionClearHandler, questionsChangedHandler);
		this.questionRepository = questionRepository;
		this.questionExtractor = questionExtractor;
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.curriculumSelectorPane = curriculumSelectorPane;
		this.bookletSupplier = bookletSupplier;
		this.examPdfSessionSupplier = examPdfSessionSupplier;
		this.selectionClearHandler = selectionClearHandler;
		this.questionsChangedHandler = questionsChangedHandler;
		this.importedQuestionActivationHandler = importedQuestionActivationHandler;
		this.questionTargetChangeAllowed = questionTargetChangeAllowed;
		this.sourceQuestionRepository = sourceQuestionRepository;
		this.sharedContextCapturePane = sharedContextCapturePane;
		this.questionSelectionTransferHandler = questionSelectionTransferHandler;
		this.questionCaptureService = questionCaptureService;
		configureControls();
		configureActions();
		buildContent();
		configurePane();
	}

	/**
	 * Accepts a proportional exam-page selection as the current pending region.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	void acceptSelection(PdfWorkspacePane.RegionSelection selection) {
		if (importedQuestion != null && !importedQuestion.getRegions().isEmpty()) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.INFORMATION, "Question regions already captured.",
					"This imported question already has its question regions. "
							+ "Only the unresolved shared preamble needs to be captured.");
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			clearCurrentSelection();
			showAlert(Alert.AlertType.WARNING, "Exam details have not been set.",
					"Enter the exam and booklet details, then click Set Exam.");
			return;
		}
		currentSelection = new QuestionRegion(booklet, selection.pageNumber(), selection.x(), selection.y(),
				selection.width(), selection.height());
		saveStatusLabel.setText("Selection pending — click Add Region or Clear");
		refreshSaveButtonState();
	}

	/**
	 * Routes a proportional exam-page selection to active shared-context capture.
	 *
	 * @param selection the selected exam-page rectangle
	 */
	void acceptSharedContextSelection(PdfWorkspacePane.RegionSelection selection) {
		sharedContextCapturePane.acceptSelection(selection);
		saveStatusLabel.setText("Shared preamble selection pending — click Add Region or Clear");
		refreshSaveButtonState();
	}

	/**
	 * Discards the current unaccepted region selection.
	 */
	void clearCurrentSelection() {
		if (sharedContextCapturePane.isCaptureMode() && sharedContextCapturePane.hasCurrentSelection()) {
			sharedContextCapturePane.clearCurrentSelection();
			refreshSaveButtonState();
			return;
		}
		currentSelection = null;
		selectionClearHandler.run();
	}

	/**
	 * Clears all transient question regions when the exam PDF changes.
	 */
	void clearForNewPdf() {
		importedQuestion = null;
		importedCaptureMode = false;
		editingQuestion = null;
		questionEditCompletedHandler = () -> {
		};
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		showNewQuestionMode();
		resetQuestionEntry();
		sharedContextCapturePane.refreshForCurrentBooklet();
		refreshImportedQuestions();
	}

	/**
	 * Clears the transient question save/update status.
	 */
	void clearSaveStatus() {
		saveStatusLabel.setText("");
	}

	/**
	 * Clears the current unaccepted shared-context selection.
	 */
	void clearSharedContextCurrentSelection() {
		sharedContextCapturePane.clearCurrentSelection();
		refreshSaveButtonState();
	}

	/**
	 * Attempts to edit a question without a completion callback.
	 *
	 * @param question the persisted question to load
	 */
	void editQuestion(Question question) {
		editQuestion(question, () -> {
		});
	}

	/**
	 * Loads a question and its ordered regions for editing after capture-transition
	 * guards and booklet activation succeed. The existing syllabus is locked.
	 *
	 * @param question             the persisted question to load
	 * @param editCompletedHandler callback when the edit is saved or cancelled
	 * @return whether the edit was started
	 * @throws NullPointerException if either argument is null
	 */
	boolean editQuestion(Question question, Runnable editCompletedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (editCompletedHandler == null) {
			throw new NullPointerException("editCompletedHandler");
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return false;
		}
		if (!importedQuestionActivationHandler.test(question)) {
			restoreCaptureModeToggle();
			return false;
		}
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		importedQuestion = null;
		importedCaptureMode = false;
		setLegacyCaptureControlsVisible(false);
		captureModeGroup.selectToggle(null);
		clearRegions();
		editingQuestion = question;
		questionEditCompletedHandler = editCompletedHandler;
		sharedContextCapturePane.refreshForCurrentBooklet();
		loadQuestionEditFields(question);
		pendingRegions.addAll(question.getRegions());
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		hideImportedClassification();
		saveQuestionButton.setText("Update Question");
		cancelQuestionEditButton.setVisible(true);
		cancelQuestionEditButton.setManaged(true);
		showCaptureHint(
				"Editing stored question " + question.getQuestionCode() + ". Exam and booklet cannot be changed.");
		refreshPreambleControls();
		saveStatusLabel.setText(
				"Editing " + question.getQuestionCode() + " — " + pendingRegions.size() + " stored region(s) loaded");
		refreshSaveButtonState();
		return true;
	}

	/**
	 * Indicates whether accepted question or automatic preamble regions would be
	 * discarded by a workflow transition.
	 *
	 * @return {@code true} when accepted transient regions exist
	 */
	boolean hasAcceptedRegions() {
		return !pendingRegions.isEmpty() || sharedContextCapturePane.hasPendingAutomaticRegion();
	}

	/**
	 * @return {@code true} while the shared-context pane owns PDF selections
	 */
	boolean isCapturingSharedContext() {
		return sharedContextCapturePane.isCaptureMode();
	}

	/**
	 * @return whether a question save transaction is currently running
	 */
	boolean isSaveInProgress() {
		return questionSaveInProgress;
	}

	/**
	 * Reloads persisted questions that still require question-region capture while
	 * retaining the selected item when it remains available.
	 */
	void refreshImportedQuestions() {
		Question selected = importedQuestion;
		List<Question> awaitingCapture = new ArrayList<>();
		for (Question question : questionRepository.findAll()) {
			if (question.getRegions().isEmpty() || question.isSharedContextUnresolved()) {
				awaitingCapture.add(question);
			}
		}
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getItems().setAll(awaitingCapture);
			Question matchingSelection = null;
			if (selected != null) {
				for (Question question : awaitingCapture) {
					if (question.getId() == selected.getId()) {
						matchingSelection = question;
						break;
					}
				}
			}
			importedQuestionBox.setValue(matchingSelection);
			importedQuestion = matchingSelection;
		} finally {
			refreshingImportedQuestions = false;
		}
	}

	/**
	 * Attempts to switch to imported-question capture, subject to the pending-state
	 * guards. A rejected transition restores the previous mode toggle.
	 */
	void showImportedQuestionCapture() {
		if ((!importedCaptureMode || editingQuestion != null) && !captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return;
		}
		try {
			backfillDerivedSourceQuestions();
			reconcileKnownSharedContexts();
		} catch (IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Shared preamble links are inconsistent.", e.getMessage());
			restoreCaptureModeToggle();
			return;
		}
		if (!importedCaptureMode || editingQuestion != null) {
			questionEditCompletedHandler = () -> {
			};
			editingQuestion = null;
			clearImportedQuestionSelection();
			importedCaptureMode = true;
		}
		selectCaptureModeToggle(true);
		refreshImportedQuestions();
		setLegacyCaptureControlsVisible(true);
		if (importedQuestion == null) {
			showImportedQueueMode();
		}
	}

	/**
	 * Makes imported-question capture controls available and refreshes their data.
	 */
	void showLegacyCaptureControls() {
		showImportedQuestionCapture();
	}

	/**
	 * Attempts to switch to new-question capture, subject to pending-state guards.
	 * Accepted state is cleared only after the transition is allowed.
	 */
	void showNewQuestionCapture() {
		if (!importedCaptureMode && editingQuestion == null) {
			selectCaptureModeToggle(false);
			setLegacyCaptureControlsVisible(false);
			return;
		}
		if (!captureModeChangeAllowed()) {
			restoreCaptureModeToggle();
			return;
		}
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		clearImportedQuestionSelection();
	}

	private void addCurrentRegion() {
		if (sharedContextCapturePane.isCaptureMode()) {
			if (!sharedContextCapturePane.hasCurrentSelection()) {
				return;
			}
			if (!sharedContextCapturePane.acceptAutomaticRegion()) {
				return;
			}
			updateQuestionCodeLock();
			hidePreambleStatus();
			if (importedQuestion != null && !importedQuestion.getRegions().isEmpty()) {
				saveStatusLabel.setText("Shared preamble captured — save the resolution.");
			} else {
				saveStatusLabel.setText("Shared preamble captured — select the question region(s).");
			}
			refreshSaveButtonState();
			return;
		}
		if (currentSelection == null) {
			return;
		}
		pendingRegions.add(currentSelection);
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void addRegionPreview(QuestionRegion region, int regionIndex) {
		try {
			BufferedImage image = questionExtractor.extractRegion(examPdfSessionSupplier.get(), region);
			ImageView imageView = new ImageView(SwingFXUtils.toFXImage(image, null));
			imageView.setPreserveRatio(true);
			imageView.fitWidthProperty()
					.bind(Bindings.createDoubleBinding(
							() -> Math.max(0.0, regionsScrollPane.getViewportBounds().getWidth() - 8.0),
							regionsScrollPane.viewportBoundsProperty()));
			imageView.setSmooth(true);
			Label label = new Label(String.format("Region %d - Page %d", regionIndex + 1, region.pageNumber()));
			Button removeButton = new Button("Remove");
			removeButton.setOnAction(_ -> removeRegion(regionIndex));
			HBox header = new HBox(SECTION_SPACING, label, removeButton);
			regionPreviewBox.getChildren().add(new VBox(REGION_PREVIEW_ITEM_SPACING, header, imageView));
		} catch (IOException e) {
			throw new RuntimeException("Unable to preview region", e);
		}
	}

	private void backfillDerivedSourceQuestions() {
		for (Question question : questionRepository.findAll()) {
			if (question.hasSourceQuestion()) {
				continue;
			}
			String sourceCode = SourceQuestionCodeParser.derive(question.getQuestionCode());
			if (sourceCode == null) {
				continue;
			}
			ExamBooklet booklet = question.getBooklet();
			SourceQuestion sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode)
					.orElseGet(() -> sourceQuestionRepository.save(booklet, sourceCode));
			questionRepository.updateCaptureRelationships(question.getId(), question.getClassification(),
					sourceQuestion, question.getSharedContext());
		}
	}

	private void buildContent() {
		legacyCaptureBox.getChildren().addAll(new Label("Question awaiting capture"), createImportedQuestionControls(),
				importedClassificationLabel, captureHintLabel);
		preambleControlsBox.getChildren().addAll(firstRegionPreambleCheckBox, preambleStatusLabel);
		setLegacyCaptureControlsVisible(false);
		getChildren().addAll(createSectionLabel("Question"), createCaptureModeControls(), legacyCaptureBox,
				createQuestionControls(), preambleControlsBox, saveStatusLabel, createCurrentSelectionControls(),
				new Separator(), new Label("Accepted regions"), regionCountLabel, createRegionsScrollPane());
	}

	private void cancelQuestionEdit() {
		if (editingQuestion == null) {
			return;
		}
		finishQuestionEdit();
	}

	private boolean captureModeChangeAllowed() {
		if (questionSaveInProgress) {
			return false;
		}
		if (currentSelection != null || sharedContextCapturePane.hasCurrentSelection()) {
			showAlert(Alert.AlertType.WARNING, "Selection pending.",
					"Add or clear the current selection before changing question capture mode.");
			return false;
		}
		if (hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			return false;
		}
		return true;
	}

	private SqliteQuestionCaptureService.Operation captureOperation() {
		if (editingQuestion != null) {
			return SqliteQuestionCaptureService.Operation.EDIT;
		}
		if (importedQuestion != null) {
			return SqliteQuestionCaptureService.Operation.IMPORTED;
		}
		return SqliteQuestionCaptureService.Operation.NEW;
	}

	private void checkImportedQuestionBox() {
		if (!refreshingImportedQuestions) {
			loadImportedQuestion(importedQuestionBox.getValue());
		}
	}

	private void clearImportedQuestionSelection() {
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(null);
		} finally {
			refreshingImportedQuestions = false;
		}
		importedQuestion = null;
		showNewQuestionMode();
		resetQuestionEntry();
	}

	private void clearPendingSelection() {
		clearCurrentSelection();
		showQuestionPendingStatus();
	}

	private void clearQuestionRegions() {
		clearRegions();
		showQuestionPendingStatus();
	}

	private void clearRegions() {
		pendingRegions.clear();
		clearCurrentSelection();
		refreshRegionPreviews();
		setRegionCountLabel(0);
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void configureActions() {
		addRegionButton.setOnAction(_ -> addCurrentRegion());
		clearRegionsButton.setOnAction(_ -> clearQuestionRegions());
		removeCurrentSelectionButton.setOnAction(_ -> clearPendingSelection());
		saveQuestionButton.setOnAction(_ -> validateQuestionForSave());
		questionCodeField.textProperty()
				.addListener((_, _, newCode) -> handleQuestionCodeChanged(newCode));
		marksField.textProperty().addListener((_, _, _) -> refreshSaveButtonState());
		curriculumSelectorPane.selectedClassificationProperty()
				.addListener((_, _, _) -> refreshSaveButtonState());
		firstRegionPreambleCheckBox.selectedProperty()
				.addListener((_, _, selected) -> handlePreambleOptionChanged(selected.booleanValue()));
		importedQuestionBox.setOnAction(_ -> checkImportedQuestionBox());
		newQuestionsModeButton.setOnAction(_ -> showNewQuestionCapture());
		importedQuestionsModeButton.setOnAction(_ -> showImportedQuestionCapture());
		cancelQuestionEditButton.setOnAction(_ -> cancelQuestionEdit());
	}

	private void configureControls() {
		questionCodeField.setId("question-code");
		questionCodeField.setPromptText("Q1");
		questionCodeField.setPrefWidth(QUESTION_CODE_FIELD_WIDTH);
		marksField.setId("question-marks");
		marksField.setPromptText("1");
		marksField.setPrefWidth(MARKS_FIELD_WIDTH);
		saveQuestionButton.setId("save-question");
		saveQuestionButton.setDisable(true);
		saveStatusLabel.setId("question-save-status");
		regionCountLabel.setId("question-region-count");
		addRegionButton.setId("add-question-region");
		removeCurrentSelectionButton.setId("clear-question-selection");
		addRegionButton.setPadding(COMPACT_BUTTON_PADDING);
		removeCurrentSelectionButton.setPadding(COMPACT_BUTTON_PADDING);
		saveStatusLabel.setStyle(SUCCESS_STATUS_STYLE);
		importedQuestionBox.setId("imported-question");
		importedQuestionBox.setPromptText("Select imported question");
		importedQuestionBox.setMaxWidth(Double.MAX_VALUE);
		importedQuestionBox.setConverter(new StringConverter<Question>() {

			@Override
			public Question fromString(String text) {
				return null;
			}

			@Override
			public String toString(Question question) {
				if (question == null) {
					return "";
				}
				ExamBooklet booklet = question.getBooklet();
				Exam exam = booklet.getExam();
				return String.format("%s %d — %s — %s — %d mark(s)", exam.getProvider().getName(), exam.getYear(),
						booklet.getName(), question.getQuestionCode(), question.getMarks());
			}
		});
		importedQuestionBox.setOnShowing(_ -> showSelectedImportedQuestionDocument());
		importedClassificationLabel.setId("imported-classification");
		importedClassificationLabel.setWrapText(true);
		importedClassificationLabel.setVisible(false);
		importedClassificationLabel.setManaged(false);
		newQuestionsModeButton.setId("capture-mode-new");
		importedQuestionsModeButton.setId("capture-mode-imported");
		newQuestionsModeButton.setToggleGroup(captureModeGroup);
		importedQuestionsModeButton.setToggleGroup(captureModeGroup);
		newQuestionsModeButton.setSelected(true);
		legacyCaptureBox.setId("legacy-question-capture");
		firstRegionPreambleCheckBox.setId("first-region-shared-preamble");
		firstRegionPreambleCheckBox.setTooltip(new Tooltip(
				"When selected, the first captured region is stored as the shared preamble for all parts of this question."));
		firstRegionPreambleCheckBox.setVisible(false);
		firstRegionPreambleCheckBox.setManaged(false);
		preambleStatusLabel.setId("shared-preamble-status");
		preambleStatusLabel.setVisible(false);
		preambleStatusLabel.setManaged(false);
		preambleStatusLabel.setWrapText(true);
		captureHintLabel.setId("question-capture-hint");
		captureHintLabel.setWrapText(true);
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
		cancelQuestionEditButton.setId("cancel-question-edit");
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
	}

	private void configurePane() {
		setSpacing(COMPACT_SPACING);
		setPadding(PANEL_PADDING);
		setStyle(BORDER_STYLE);
	}

	private HBox createCaptureModeControls() {
		HBox controls = new HBox(CONTROL_SPACING, newQuestionsModeButton, importedQuestionsModeButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createCurrentSelectionControls() {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox controls = new HBox(CONTROL_SPACING, addRegionButton, removeCurrentSelectionButton, spacer,
				saveQuestionButton, cancelQuestionEditButton);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createImportedQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, importedQuestionBox);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private HBox createQuestionControls() {
		HBox controls = new HBox(CONTROL_SPACING, new Label("Question number"), questionCodeField, new Label("Marks"),
				marksField);
		controls.setAlignment(Pos.CENTER_LEFT);
		return controls;
	}

	private ScrollPane createRegionsScrollPane() {
		regionsScrollPane.setFitToWidth(true);
		regionsScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		regionsScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		regionsScrollPane.setMinHeight(0);
		regionsScrollPane.setMaxHeight(REGIONS_VIEWPORT_HEIGHT);
		regionsScrollPane.prefHeightProperty()
				.bind(Bindings.createDoubleBinding(
						() -> Math.min(REGIONS_VIEWPORT_HEIGHT, regionPreviewBox.getLayoutBounds().getHeight() + 4.0),
						regionPreviewBox.layoutBoundsProperty()));
		regionsScrollPane.setVisible(false);
		regionsScrollPane.setManaged(false);
		return regionsScrollPane;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(SECTION_HEADING_STYLE);
		return label;
	}

	private boolean editingSourceMatches(String questionCode) {
		if (editingQuestion == null) {
			return true;
		}
		String derivedSourceCode = SourceQuestionCodeParser.derive(questionCode);
		if (!editingQuestion.hasSourceQuestion()) {
			return derivedSourceCode == null;
		}
		return editingQuestion.getSourceQuestion().getSourceQuestionCode().equals(derivedSourceCode);
	}

	private Question findExistingQuestionWithSameCode() {
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			return null;
		}
		String questionCode = questionCodeField.getText().trim();
		for (Question question : questionRepository.findAll()) {
			if (question.getBooklet().getId() == booklet.getId() && question.getQuestionCode().equals(questionCode)) {
				return question;
			}
		}
		return null;
	}

	private String findQuestionDetailsValidationError() {
		int effectiveRegionCount = pendingRegions.size();
		if (importedQuestion != null && !importedQuestion.getRegions().isEmpty()) {
			effectiveRegionCount = importedQuestion.getRegions().size();
		}
		String validationError = QuestionCaptureValidator.findError(new QuestionCaptureValidator.State(
				bookletSupplier.get() != null, questionCodeField.getText().trim(), marksField.getText().trim(),
				curriculumSelectionModel.getSubject() != null, curriculumSelectionModel.getUnit() != null,
				curriculumSelectionModel.getTopic() != null, curriculumSelectionModel.getClassification() != null,
				currentSelection != null, effectiveRegionCount));
		return validationError;
	}

	private SharedQuestionContext findSharedContextForSourceQuestion(SourceQuestion sourceQuestion) {
		SharedQuestionContext matchingContext = null;
		for (Question question : questionRepository.findAll()) {
			if (question.getBooklet().getId() != sourceQuestion.getBooklet().getId()) {
				continue;
			}
			if (!question.hasSourceQuestion()) {
				continue;
			}
			if (question.getSourceQuestion().getId() != sourceQuestion.getId()) {
				continue;
			}
			if (!question.hasSharedContext()) {
				continue;
			}
			SharedQuestionContext candidate = question.getSharedContext();
			if (matchingContext != null && matchingContext.getId() != candidate.getId()) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared preamble links");
			}
			matchingContext = candidate;
		}
		return matchingContext;
	}

	private String findSharedContextValidationError() {
		if (sharedContextCapturePane.isCaptureMode()) {
			return "Capture the shared preamble region and click Add Region before saving the question.";
		}
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		ExamBooklet booklet = bookletSupplier.get();
		SourceQuestion sourceQuestion = null;
		if (booklet != null && sourceCode != null) {
			sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).orElse(null);
		}
		SharedQuestionContext existingContext = null;
		if (sourceQuestion != null) {
			existingContext = findSharedContextForSourceQuestion(sourceQuestion);
		}
		boolean contextAvailable = sharedContextCapturePane.getSelectedContext() != null
				|| sharedContextCapturePane.hasPendingAutomaticRegion() || existingContext != null;
		boolean unresolvedSharedContext = (importedQuestion != null && importedQuestion.isSharedContextUnresolved())
				|| (editingQuestion != null && editingQuestion.isSharedContextUnresolved());
		if (unresolvedSharedContext && !contextAvailable) {
			return "This imported question requires a shared preamble. " + "Capture the preamble as the first region.";
		}
		if (sourceQuestion != null && sourceQuestion.getPreambleStatus() == PreambleStatus.PRESENT
				&& !contextAvailable) {
			return "Question " + sourceCode + " is recorded as having a shared preamble, "
					+ "but its shared preamble could not be found.";
		}
		return null;
	}

	private String findValidationError() {
		String validationError = findQuestionDetailsValidationError();
		if (validationError != null) {
			return validationError;
		}
		return findSharedContextValidationError();
	}

	private void finishQuestionEdit() {
		Runnable editCompletedHandler = questionEditCompletedHandler;
		questionEditCompletedHandler = () -> {
		};
		editingQuestion = null;
		importedCaptureMode = false;
		selectCaptureModeToggle(false);
		setLegacyCaptureControlsVisible(false);
		showNewQuestionMode();
		resetQuestionEntry();
		editCompletedHandler.run();
	}

	private void handlePreambleOptionChanged(boolean selected) {
		if (refreshingPreambleControls) {
			return;
		}
		try {
			String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
			if (!selected) {
				sharedContextCapturePane.cancelAutomaticContext();
				hidePreambleStatus();
				updateQuestionCodeLock();
				return;
			}
			if (sourceCode == null) {
				setPreambleCheckBoxSelected(false);
				return;
			}
			String label = "Question " + sourceCode + " preamble";
			/*
			 * If the user drew the rectangle before identifying this as a multipart
			 * question, reinterpret that same pending rectangle as the shared preamble.
			 */
			if (currentSelection != null) {
				boolean started = sharedContextCapturePane.beginAutomaticContext(label, currentSelection);
				if (!started) {
					setPreambleCheckBoxSelected(false);
					return;
				}
				if (!questionSelectionTransferHandler.getAsBoolean()) {
					sharedContextCapturePane.cancelAutomaticContext();
					setPreambleCheckBoxSelected(false);
					return;
				}
				/*
				 * The rectangle now belongs to SharedContextCapturePane. Do not clear the PDF
				 * selection.
				 */
				currentSelection = null;
				saveStatusLabel.setText("Shared preamble selection pending " + "— click Add Region or Clear");
				updateQuestionCodeLock();
				return;
			}
			boolean started = sharedContextCapturePane.beginAutomaticContext(label);
			if (!started) {
				setPreambleCheckBoxSelected(false);
				return;
			}
			updateQuestionCodeLock();
		} finally {
			refreshSaveButtonState();
		}
	}

	private void handleQuestionCodeChanged(String newCode) {
		if (editingQuestion != null && !loadingQuestionEdit && !editingSourceMatches(newCode)) {
			sharedContextCapturePane.selectContext(null);
		}
		refreshPreambleControls();
		refreshSaveButtonState();
	}

	private void hideCaptureHint() {
		captureHintLabel.setVisible(false);
		captureHintLabel.setManaged(false);
	}

	private void hideImportedClassification() {
		importedClassificationLabel.setText("");
		importedClassificationLabel.setVisible(false);
		importedClassificationLabel.setManaged(false);
	}

	private void hidePreambleControls() {
		setPreambleCheckBoxSelected(false);
		setPreambleCheckBoxVisible(false);
		firstRegionPreambleCheckBox.setDisable(false);
		hidePreambleStatus();
	}

	private void hidePreambleStatus() {
		preambleStatusLabel.setText("");
		preambleStatusLabel.setStyle("");
		preambleStatusLabel.setVisible(false);
		preambleStatusLabel.setManaged(false);
	}

	private void loadImportedQuestion(Question question) {
		Question previousQuestion = importedQuestion;
		if (question != previousQuestion && hasAcceptedRegions() && !questionTargetChangeAllowed.getAsBoolean()) {
			restoreImportedQuestionSelection(previousQuestion);
			return;
		}
		if (question != null && !importedQuestionActivationHandler.test(question)) {
			restoreImportedQuestionSelection(previousQuestion);
			return;
		}
		clearRegions();
		importedQuestion = question;
		sharedContextCapturePane.refreshForCurrentBooklet();
		if (question == null) {
			resetQuestionEntry();
			if (importedCaptureMode) {
				showImportedQueueMode();
			} else {
				showNewQuestionMode();
			}
			refreshSaveButtonState();
			return;
		}
		showImportedQuestionMode(question);
	}

	private void loadQuestionEditFields(Question question) {
		loadingQuestionEdit = true;
		try {
			questionCodeField.setText(question.getQuestionCode());
			marksField.setText(Integer.toString(question.getMarks()));
			curriculumSelectorPane.selectClassificationPath(question.getClassification());
			if (question.hasSharedContext()) {
				sharedContextCapturePane.selectContext(question.getSharedContext());
			}
		} finally {
			loadingQuestionEdit = false;
		}
	}

	private SqliteQuestionCaptureService.PendingSharedContext pendingSharedContextForSave() {
		if (!sharedContextCapturePane.hasPendingAutomaticRegion()) {
			return null;
		}
		return new SqliteQuestionCaptureService.PendingSharedContext(
				sharedContextCapturePane.getPendingAutomaticContextLabel(),
				sharedContextCapturePane.getPendingAutomaticContextRegions());
	}

	private void reconcileKnownSharedContexts() {
		List<Long> processedSourceQuestionIds = new ArrayList<>();
		for (Question question : questionRepository.findAll()) {
			if (!question.hasSourceQuestion() || !question.hasSharedContext()) {
				continue;
			}
			SourceQuestion sourceQuestion = question.getSourceQuestion();
			if (processedSourceQuestionIds.contains(sourceQuestion.getId())) {
				continue;
			}
			SharedQuestionContext sharedContext = findSharedContextForSourceQuestion(sourceQuestion);
			if (sharedContext != null) {
				questionRepository.applySharedContextToSourceQuestion(sourceQuestion, sharedContext);
			}
			processedSourceQuestionIds.add(sourceQuestion.getId());
		}
	}

	private void refreshPreambleControls() {
		sharedContextCapturePane.cancelAutomaticContext();
		String sourceCode = SourceQuestionCodeParser.derive(questionCodeField.getText());
		if (sourceCode == null) {
			if (importedQuestion != null && importedQuestion.isSharedContextUnresolved()) {
				hidePreambleControls();
				boolean started = sharedContextCapturePane
						.beginAutomaticContext("Question " + importedQuestion.getQuestionCode() + " preamble");
				if (started) {
					showPreambleRequiredStatus("Shared preamble required — capture it as the first region.");
				}
				return;
			}
			hidePreambleControls();
			return;
		}
		ExamBooklet booklet = bookletSupplier.get();
		if (booklet == null) {
			hidePreambleControls();
			return;
		}
		if (showImportedPreamble(sourceCode)) {
			return;
		}
		SourceQuestion sourceQuestion = sourceQuestionRepository.findByBookletAndCode(booklet, sourceCode).orElse(null);
		if (sourceQuestion != null && showStoredPreamble(sourceCode, sourceQuestion)) {
			return;
		}
		boolean required = importedQuestion != null && importedQuestion.isSharedContextUnresolved();
		showPreambleOption(sourceCode, required);
	}

	private void refreshRegionPreviews() {
		regionPreviewBox.getChildren().clear();
		for (int i = 0; i < pendingRegions.size(); i++) {
			addRegionPreview(pendingRegions.get(i), i);
		}
		updateRegionsScrollPane();
	}

	private void refreshSaveButtonState() {
		addRegionButton.setText(sharedContextCapturePane.isCaptureMode() ? "Add Preamble" : "Add Region");
		boolean ready;
		try {
			ready = findValidationError() == null;
		} catch (IllegalStateException e) {
			/*
			 * An inconsistent stored relationship must never make Save available. Save-time
			 * validation remains the defensive backstop.
			 */
			ready = false;
		}
		saveQuestionButton.setDisable(!ready);
	}

	private void removeRegion(int regionIndex) {
		pendingRegions.remove(regionIndex);
		refreshRegionPreviews();
		setRegionCountLabel(pendingRegions.size());
		showQuestionPendingStatus();
		updateQuestionCodeLock();
		refreshSaveButtonState();
	}

	private void resetAfterQuestionSave(int previousImportedIndex) {
		importedQuestion = null;
		resetQuestionEntry();
		refreshImportedQuestions();
		if (previousImportedIndex < 0) {
			showNewQuestionMode();
			return;
		}
		if (importedQuestionBox.getItems().isEmpty()) {
			showImportedQueueMode();
			return;
		}
		int nextIndex = Math.min(previousImportedIndex, importedQuestionBox.getItems().size() - 1);
		Question nextQuestion = importedQuestionBox.getItems().get(nextIndex);
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.getSelectionModel().select(nextIndex);
		} finally {
			refreshingImportedQuestions = false;
		}
		loadImportedQuestion(nextQuestion);
	}

	private void resetQuestionEntry() {
		sharedContextCapturePane.clearForQuestion();
		hidePreambleControls();
		questionCodeField.clear();
		questionCodeField.setDisable(false);
		marksField.clear();
		clearRegions();
		curriculumSelectorPane.clearClassificationBelowSubject();
		refreshSaveButtonState();
	}

	private void restoreCaptureModeToggle() {
		if (editingQuestion != null) {
			captureModeGroup.selectToggle(null);
			return;
		}
		selectCaptureModeToggle(importedCaptureMode);
	}

	private void restoreImportedQuestionSelection(Question question) {
		refreshingImportedQuestions = true;
		try {
			importedQuestionBox.setValue(question);
		} finally {
			refreshingImportedQuestions = false;
		}
	}

	private void saveQuestion() {
		int previousImportedIndex = -1;
		boolean hadStoredRegions = false;
		Question existingQuestion = null;
		boolean editing = editingQuestion != null;
		boolean imported = importedQuestion != null;
		if (editing) {
			existingQuestion = editingQuestion;
		} else if (imported) {
			existingQuestion = importedQuestion;
			previousImportedIndex = selectedImportedQuestionIndex();
			hadStoredRegions = !importedQuestion.getRegions().isEmpty();
		}
		SqliteQuestionCaptureService.Request request = new SqliteQuestionCaptureService.Request(captureOperation(),
				bookletSupplier.get(), existingQuestion, questionCodeField.getText().trim(),
				Integer.parseInt(marksField.getText().trim()), List.copyOf(pendingRegions),
				curriculumSelectionModel.getClassification(), sharedContextCapturePane.getSelectedContext(),
				pendingSharedContextForSave());
		int savedPreviousImportedIndex = previousImportedIndex;
		boolean savedHadStoredRegions = hadStoredRegions;
		questionSaveInProgress = true;
		setDisable(true);
		saveStatusLabel.setText("Saving " + request.questionCode() + "...");
		Task<Question> saveTask = new Task<>() {

			@Override
			protected Question call() {
				return questionCaptureService.save(request);
			}
		};
		saveTask.setOnSucceeded(_ -> {
			questionSaveInProgress = false;
			setDisable(false);
			Question question = saveTask.getValue();
			if (editing) {
				showSavedQuestionStatus("Updated", question);
				questionsChangedHandler.run();
				finishQuestionEdit();
				return;
			}
			if (imported) {
				showSavedQuestionStatus(savedHadStoredRegions ? "Resolved" : "Captured", question);
			} else {
				showSavedQuestionStatus("Saved", question);
			}
			questionsChangedHandler.run();
			resetAfterQuestionSave(savedPreviousImportedIndex);
		});
		saveTask.setOnFailed(_ -> {
			questionSaveInProgress = false;
			setDisable(false);
			saveStatusLabel.setText("Save failed — current question retained");
			showAlert(Alert.AlertType.ERROR, "Question could not be saved.", "The question was not saved. "
					+ "Your current question details and accepted regions " + "have been retained.");
		});
		Thread saveThread = new Thread(saveTask, "question-save");
		saveThread.setDaemon(true);
		saveThread.start();
	}

	private void selectCaptureModeToggle(boolean imported) {
		if (imported) {
			importedQuestionsModeButton.setSelected(true);
		} else {
			newQuestionsModeButton.setSelected(true);
		}
	}

	private int selectedImportedQuestionIndex() {
		int selectedIndex = importedQuestionBox.getSelectionModel().getSelectedIndex();
		return selectedIndex < 0 ? 0 : selectedIndex;
	}

	private void setLegacyCaptureControlsVisible(boolean visible) {
		legacyCaptureBox.setVisible(visible);
		legacyCaptureBox.setManaged(visible);
	}

	private void setPreambleCheckBoxSelected(boolean selected) {
		refreshingPreambleControls = true;
		try {
			firstRegionPreambleCheckBox.setSelected(selected);
		} finally {
			refreshingPreambleControls = false;
		}
	}

	private void setPreambleCheckBoxVisible(boolean visible) {
		firstRegionPreambleCheckBox.setVisible(visible);
		firstRegionPreambleCheckBox.setManaged(visible);
	}

	private void setRegionCountLabel(int count) {
		regionCountLabel.setText(String.format("Regions: %d", count));
	}

	private void showAlert(Alert.AlertType type, String header, String message) {
		Alert alert = new Alert(type);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showCaptureHint(String text) {
		captureHintLabel.setText(text);
		captureHintLabel.setVisible(true);
		captureHintLabel.setManaged(true);
	}

	private void showImportedClassification(Question question) {
		importedClassificationLabel.setText("Imported classification: " + question.getClassification().getCode() + " — "
				+ question.getClassification().getName());
		importedClassificationLabel.setVisible(true);
		importedClassificationLabel.setManaged(true);
	}

	private boolean showImportedPreamble(String sourceCode) {
		if (importedQuestion == null || !importedQuestion.hasSharedContext()) {
			return false;
		}
		sharedContextCapturePane.selectContext(importedQuestion.getSharedContext());
		hidePreambleControls();
		showPreambleStatus("Shared preamble: Question " + sourceCode);
		return true;
	}

	private void showImportedQuestionMode(Question question) {
		questionCodeField.setText(question.getQuestionCode());
		marksField.setText(Integer.toString(question.getMarks()));
		if (question.hasSharedContext()) {
			sharedContextCapturePane.selectContext(question.getSharedContext());
		}
		curriculumSelectorPane.selectClassificationPath(question.getClassification());
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(true);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		showImportedClassification(question);
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		refreshPreambleControls();
		if (question.getRegions().isEmpty()) {
			saveQuestionButton.setText("Save Question");
			if (question.isSharedContextUnresolved()) {
				hideCaptureHint();
			} else {
				showCaptureHint("Capture the question region(s).");
			}
		} else {
			saveQuestionButton.setText("Save Resolution");
			hideCaptureHint();
		}
		showQuestionPendingStatus();
		refreshSaveButtonState();
	}

	private void showImportedQueueMode() {
		questionCodeField.setDisable(true);
		marksField.setDisable(true);
		curriculumSelectorPane.setSyllabusContextLocked(false);
		curriculumSelectorPane.setDisable(true);
		hideImportedClassification();
		hidePreambleControls();
		saveQuestionButton.setText("Save Resolution");
		saveQuestionButton.setDisable(true);
		if (importedQuestionBox.getItems().isEmpty()) {
			showCaptureHint("No imported questions are awaiting capture or resolution.");
		} else {
			showCaptureHint("Select an imported question awaiting capture or resolution.");
		}
	}

	private void showNewQuestionMode() {
		questionCodeField.setDisable(false);
		marksField.setDisable(false);
		curriculumSelectorPane.setDisable(false);
		curriculumSelectorPane.setSyllabusContextLocked(false);
		cancelQuestionEditButton.setVisible(false);
		cancelQuestionEditButton.setManaged(false);
		hideImportedClassification();
		saveQuestionButton.setText("Save Question");
		hideCaptureHint();
	}

	private void showPreambleOption(String sourceCode, boolean required) {
		setPreambleCheckBoxVisible(true);
		firstRegionPreambleCheckBox.setDisable(required);
		setPreambleCheckBoxSelected(required);
		if (!required) {
			hidePreambleStatus();
			return;
		}
		boolean started = sharedContextCapturePane.beginAutomaticContext("Question " + sourceCode + " preamble");
		if (!started) {
			setPreambleCheckBoxSelected(false);
			return;
		}
		showPreambleRequiredStatus("Shared preamble required — capture it as the first region.");
	}

	private void showPreambleRequiredStatus(String text) {
		preambleStatusLabel.setStyle(REQUIRED_STATUS_STYLE);
		preambleStatusLabel.setText(text);
		preambleStatusLabel.setVisible(true);
		preambleStatusLabel.setManaged(true);
	}

	private void showPreambleStatus(String text) {
		preambleStatusLabel.setStyle("");
		preambleStatusLabel.setText(text);
		preambleStatusLabel.setVisible(true);
		preambleStatusLabel.setManaged(true);
	}

	private void showQuestionPendingStatus() {
		String questionCode = questionCodeField.getText().trim();
		String prefix = questionCode.isBlank() ? "Question pending" : "Pending " + questionCode;
		saveStatusLabel.setText(String.format("%s — %d region(s) accepted", prefix, pendingRegions.size()));
	}

	private void showSavedQuestionStatus(String action, Question question) {
		saveStatusLabel.setText(String.format("%s %s (%d mark(s), %d region(s))", action, question.getQuestionCode(),
				question.getMarks(), question.getRegions().size()));
	}

	private void showSelectedImportedQuestionDocument() {
		Question question = importedQuestionBox.getValue();
		if (question == null) {
			return;
		}
		importedQuestionActivationHandler.test(question);
	}

	private boolean showStoredPreamble(String sourceCode, SourceQuestion sourceQuestion) {
		SharedQuestionContext existingContext = findSharedContextForSourceQuestion(sourceQuestion);
		if (existingContext != null) {
			sharedContextCapturePane.selectContext(existingContext);
			hidePreambleControls();
			showPreambleStatus("Shared preamble: Question " + sourceCode);
			return true;
		}
		if (sourceQuestion.getPreambleStatus() == PreambleStatus.NONE) {
			hidePreambleControls();
			return true;
		}
		if (sourceQuestion.getPreambleStatus() == PreambleStatus.PRESENT) {
			hidePreambleControls();
			showPreambleStatus("Shared preamble for Question " + sourceCode + " could not be found.");
			return true;
		}
		return false;
	}

	private void updateQuestionCodeLock() {
		if (importedQuestion != null) {
			questionCodeField.setDisable(true);
			return;
		}
		/*
		 * Ordinary accepted question regions do not lock the question number. The user
		 * may still correct or enter metadata before Save.
		 *
		 * A captured shared preamble does lock the number because changing the source
		 * question would change ownership of that preamble.
		 */
		questionCodeField.setDisable(sharedContextCapturePane.hasPendingAutomaticRegion());
	}

	private void updateRegionsScrollPane() {
		boolean hasRegions = !pendingRegions.isEmpty();
		regionsScrollPane.setVisible(hasRegions);
		regionsScrollPane.setManaged(hasRegions);
	}

	private void validateDependencies(QuestionRepository questionRepository,
			SourceQuestionRepository sourceQuestionRepository, SqliteQuestionCaptureService questionCaptureService,
			SharedContextCapturePane sharedContextCapturePane, QuestionExtractor questionExtractor,
			CurriculumSelectionModel curriculumSelectionModel, CurriculumSelectorPane curriculumSelectorPane,
			Supplier<ExamBooklet> bookletSupplier, Supplier<PdfSession> examPdfSessionSupplier,
			Predicate<Question> importedQuestionActivationHandler, BooleanSupplier questionTargetChangeAllowed,
			BooleanSupplier questionSelectionTransferHandler, Runnable selectionClearHandler,
			Runnable questionsChangedHandler) {
		if (questionRepository == null) {
			throw new NullPointerException("questionRepository");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		if (curriculumSelectionModel == null) {
			throw new NullPointerException("curriculumSelectionModel");
		}
		if (curriculumSelectorPane == null) {
			throw new NullPointerException("curriculumSelectorPane");
		}
		if (bookletSupplier == null) {
			throw new NullPointerException("bookletSupplier");
		}
		if (examPdfSessionSupplier == null) {
			throw new NullPointerException("examPdfSessionSupplier");
		}
		if (selectionClearHandler == null) {
			throw new NullPointerException("selectionClearHandler");
		}
		if (questionsChangedHandler == null) {
			throw new NullPointerException("questionsChangedHandler");
		}
		if (importedQuestionActivationHandler == null) {
			throw new NullPointerException("importedQuestionActivationHandler");
		}
		if (questionTargetChangeAllowed == null) {
			throw new NullPointerException("questionTargetChangeAllowed");
		}
		if (sourceQuestionRepository == null) {
			throw new NullPointerException("sourceQuestionRepository");
		}
		if (sharedContextCapturePane == null) {
			throw new NullPointerException("sharedContextCapturePane");
		}
		if (questionSelectionTransferHandler == null) {
			throw new NullPointerException("questionSelectionTransferHandler");
		}
		if (questionCaptureService == null) {
			throw new NullPointerException("questionCaptureService");
		}
	}

	private void validateQuestionForSave() {
		String validationError = findValidationError();
		if (validationError != null) {
			showAlert(Alert.AlertType.WARNING, "Question is incomplete.", validationError);
			return;
		}
		if (importedQuestion == null) {
			Question existingQuestion = findExistingQuestionWithSameCode();
			boolean duplicateOtherQuestion = existingQuestion != null
					&& (editingQuestion == null || existingQuestion.getId() != editingQuestion.getId());
			if (duplicateOtherQuestion) {
				showAlert(Alert.AlertType.WARNING, "Question already exists.",
						"Question " + existingQuestion.getQuestionCode() + " already exists for this booklet.");
				return;
			}
		}
		saveQuestion();
	}
}
