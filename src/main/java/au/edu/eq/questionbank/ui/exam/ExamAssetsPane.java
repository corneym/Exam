package au.edu.eq.questionbank.ui.exam;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Presents the main-window Exam/Assets workspace for the current Working
 * Subject.
 * <p>
 * This first workspace increment is intentionally read-only. It establishes the
 * authoritative persisted Exam selection and asset presentation before later
 * increments add editing and asset-management actions.
 */
public final class ExamAssetsPane extends VBox {

	private static final double SPACING = 8.0;
	private static final double ROW_SPACING = 4.0;
	private static final Insets SECTION_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String HEADING_STYLE = "-fx-font-weight: bold;";
	private static final int YEAR_LOOKBACK_YEARS = 15;
	private static final List<String> DEFAULT_BOOKLET_NAME_SUGGESTIONS = List.of("MCQ", "Paper 1", "Paper 2",
			"Topic Test");
	private static final String NO_ANSWER_BOOKLET = "No Answer Booklet";
	private static final String WORKSPACE_SECTION_STYLE = "-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;"
			+ "-fx-border-radius: 3;";
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final ExamMetadataOptionsRepository optionsRepository;
	private final ExamCorrectionHandler correctionHandler;
	private final ComboBox<Exam> examBox = new ComboBox<>();
	private final Label stateLabel = new Label("State: —");
	private final ComboBox<String> providerField = new ComboBox<>();
	private final ComboBox<Integer> yearField = new ComboBox<>();
	private final ComboBox<String> assessmentField = new ComboBox<>();
	private final VBox questionBookletsBox = new VBox(ROW_SPACING);
	private final VBox answerBookletsBox = new VBox(ROW_SPACING);
	private final Button editExamButton = new Button("Edit");
	private final Button cancelButton = new Button("Cancel");
	private final Button saveButton = new Button("Save");
	private boolean editingExamDetails;
	private final Consumer<ExamBooklet> questionBookletViewHandler;
	private final Consumer<AnswerFile> answerFileViewHandler;
	private final Supplier<ExamBooklet> activeBookletSupplier;
	private final Consumer<ExamBooklet> captureBookletHandler;
	private final ToggleGroup questionBookletSelectionGroup = new ToggleGroup();
	private final Button useSelectedBookletButton = new Button("Use Selected Booklet for Capture");

	// Booklet labels from the selected Exam extend the standard reusable
	// suggestions.
	private List<String> bookletNameSuggestions = DEFAULT_BOOKLET_NAME_SUGGESTIONS;
	private final List<QuestionBookletEditor> questionBookletEditors = new ArrayList<>();
	private QuestionBookletEditor editingBookletEditor;
	private final Consumer<ExamBooklet> bookletMetadataUpdatedHandler;
	private List<AnswerFile> availableAnswerFiles = List.of();
	private final Supplier<Path> answerBookletSourceChooser;
	private final AnswerBookletCreationHandler answerBookletCreationHandler;
	private final Button addAnswerBookletButton = new Button("Add Answer Booklet");
	private PendingAnswerBookletEditor pendingAnswerBookletEditor;
	private final Supplier<Path> questionBookletSourceChooser;
	private final QuestionBookletCreationHandler questionBookletCreationHandler;
	private final Button addQuestionBookletButton = new Button("Add Question Booklet");
	private PendingQuestionBookletEditor pendingQuestionBookletEditor;
	private final Predicate<Path> questionBookletSourcePreviewHandler;
	private final Runnable questionBookletSourcePreviewCloseHandler;
	private boolean pendingQuestionBookletPreviewOpen;

	// TODO
	// The EXAM label and its components should be surrounded with a border. The
	// QUESTION BOOKLETS label and its components should be surrounded with a
	// border. The ANSWER BOOKLETS and its components should be surrounded with a
	// border. Low priority but a must for this sprint and slice.
	// New Exam is a workspace-level structural transaction. It reuses the existing
	// Exam Details controls but does not create another Subject selector.
	private final Button addNewExamButton = new Button("Add New Exam");
	private final Button clearNewExamButton = new Button("Clear");
	private final Button cancelNewExamButton = new Button("Cancel");
	private final Button saveNewExamButton = new Button("Save Exam");
	private HBox examDetailsActionRow;
	private HBox newExamActionRow;
	private Subject workingSubject;
	private boolean creatingNewExam;
	private Long newExamReturnExamId;

	// Selecting an Exam while applying a preloaded Subject snapshot must not
	// trigger
	// another synchronous SQLite read through the ordinary ComboBox listener.
	private boolean applyingSubjectSnapshot;

	/**
	 * Creates the Exam/Assets workspace.
	 *
	 * @param examWriter                               authoritative Exam/booklet
	 *                                                 persistence
	 * @param answerWriter                             authoritative AnswerFile
	 *                                                 persistence
	 * @param optionsRepository                        reusable Exam metadata labels
	 * @param correctionHandler                        authoritative Exam metadata
	 *                                                 correction
	 * @param questionBookletViewHandler               opens a Question booklet
	 *                                                 read-only
	 * @param answerFileViewHandler                    opens an Answer booklet
	 *                                                 read-only
	 * @param activeBookletSupplier                    current authoritative capture
	 *                                                 booklet
	 * @param captureBookletHandler                    activates a selected booklet
	 *                                                 for capture
	 * @param bookletMetadataUpdatedHandler            refreshes active capture
	 *                                                 state after a booklet
	 *                                                 metadata correction
	 * @param answerBookletSourceChooser               chooses a source PDF for a
	 *                                                 new Answer booklet
	 * @param answerBookletCreationHandler             imports and persists the new
	 *                                                 Answer asset * @throws
	 *                                                 NullPointerException if any
	 *                                                 argument is {@code null}
	 * @param questionBookletSourceChooser             chooses a source PDF for a
	 *                                                 new Question booklet
	 * @param questionBookletCreationHandler           imports and persists a new
	 *                                                 Question booklet * @param
	 *                                                 questionBookletSourcePreviewHandler
	 *                                                 opens a selected,
	 *                                                 not-yet-persisted Question
	 *                                                 PDF for read-only inspection
	 * @param questionBookletSourcePreviewCloseHandler closes the temporary source
	 *                                                 preview and restores the
	 *                                                 previous PDF workspace
	 *                                                 document
	 */
	public ExamAssetsPane(SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			ExamMetadataOptionsRepository optionsRepository, ExamCorrectionHandler correctionHandler,
			Consumer<ExamBooklet> questionBookletViewHandler, Consumer<AnswerFile> answerFileViewHandler,
			Supplier<ExamBooklet> activeBookletSupplier, Consumer<ExamBooklet> captureBookletHandler,
			Consumer<ExamBooklet> bookletMetadataUpdatedHandler, Supplier<Path> answerBookletSourceChooser,
			AnswerBookletCreationHandler answerBookletCreationHandler, Supplier<Path> questionBookletSourceChooser,
			Predicate<Path> questionBookletSourcePreviewHandler, Runnable questionBookletSourcePreviewCloseHandler,
			QuestionBookletCreationHandler questionBookletCreationHandler) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (optionsRepository == null) {
			throw new NullPointerException("optionsRepository");
		}
		if (correctionHandler == null) {
			throw new NullPointerException("correctionHandler");
		}
		if (questionBookletViewHandler == null) {
			throw new NullPointerException("questionBookletViewHandler");
		}
		if (answerFileViewHandler == null) {
			throw new NullPointerException("answerFileViewHandler");
		}
		if (activeBookletSupplier == null) {
			throw new NullPointerException("activeBookletSupplier");
		}
		if (captureBookletHandler == null) {
			throw new NullPointerException("captureBookletHandler");
		}
		if (bookletMetadataUpdatedHandler == null) {
			throw new NullPointerException("bookletMetadataUpdatedHandler");
		}
		if (answerBookletSourceChooser == null) {
			throw new NullPointerException("answerBookletSourceChooser");
		}
		if (answerBookletCreationHandler == null) {
			throw new NullPointerException("answerBookletCreationHandler");
		}
		if (questionBookletSourceChooser == null) {
			throw new NullPointerException("questionBookletSourceChooser");
		}
		if (questionBookletCreationHandler == null) {
			throw new NullPointerException("questionBookletCreationHandler");
		}
		if (questionBookletSourcePreviewHandler == null) {
			throw new NullPointerException("questionBookletSourcePreviewHandler");
		}
		if (questionBookletSourcePreviewCloseHandler == null) {
			throw new NullPointerException("questionBookletSourcePreviewCloseHandler");
		}
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
		this.optionsRepository = optionsRepository;
		this.correctionHandler = correctionHandler;
		this.questionBookletViewHandler = questionBookletViewHandler;
		this.answerFileViewHandler = answerFileViewHandler;
		this.activeBookletSupplier = activeBookletSupplier;
		this.captureBookletHandler = captureBookletHandler;
		this.bookletMetadataUpdatedHandler = bookletMetadataUpdatedHandler;

		// Source selection and managed PDF import are application responsibilities;
		// this pane coordinates only the editing transaction.
		this.questionBookletSourceChooser = questionBookletSourceChooser;
		this.questionBookletCreationHandler = questionBookletCreationHandler;

		// Native source selection and managed-file persistence remain
		// application-owned;
		// this pane owns only the Exam/Assets editing workflow.
		this.answerBookletSourceChooser = answerBookletSourceChooser;
		this.answerBookletCreationHandler = answerBookletCreationHandler;

		// Previewing the selected pre-persistence PDF still belongs to the application
		// because the shared PdfWorkspacePane is application-owned.
		this.questionBookletSourcePreviewHandler = questionBookletSourcePreviewHandler;
		this.questionBookletSourcePreviewCloseHandler = questionBookletSourcePreviewCloseHandler;

		// Exam/Assets owns structural asset editing while application-level callbacks
		// keep an already-active Capture booklet synchronised after persistence
		// changes.
		configureControls();
		buildContent();
		setId("exam-assets-workspace");
		setSpacing(SPACING);
	}

	/**
	 * Publishes a previously loaded Subject snapshot to Exam/Assets.
	 *
	 * @param snapshot immutable persistence snapshot
	 */
	public void applySubjectSnapshot(SubjectSnapshot snapshot) {
		if (snapshot == null) {
			throw new NullPointerException("snapshot");
		}
		if (!Objects.equals(workingSubject, snapshot.subject())) {

			// The application-level generation guard normally rejects stale work first.
			// Retain this pane-level check so stale data is also harmless if called
			// directly.
			return;
		}
		applyingSubjectSnapshot = true;
		try {
			examBox.getSelectionModel().clearSelection();
			examBox.getItems().clear();
			clearSelectedExam();
			refreshMetadataOptions();
			examBox.getItems().setAll(snapshot.exams());
			ExamSnapshot selectedExam = snapshot.selectedExam();
			if (selectedExam != null) {

				// Selecting the preloaded Exam must not invoke the ordinary persistence
				// listener while this snapshot is being published.
				examBox.getSelectionModel().select(selectedExam.exam());
				applyExamSnapshot(selectedExam);
			}
			updateAddNewExamState();
		} finally {
			applyingSubjectSnapshot = false;
		}
	}

	/**
	 * Immediately removes presentation belonging to the previous Working Subject
	 * while its replacement persistence snapshot is loading.
	 *
	 * @param subject newly accepted Working Subject, or {@code null}
	 */
	public void beginSubjectRefresh(Subject subject) {
		creatingNewExam = false;
		newExamReturnExamId = null;
		workingSubject = subject;
		restoreNormalExamPresentation();
		applyingSubjectSnapshot = true;
		try {

			// Old Subject data must disappear immediately rather than remain visible while
			// the replacement snapshot is being loaded.
			examBox.getSelectionModel().clearSelection();
			examBox.getItems().clear();
			clearSelectedExam();
			refreshMetadataOptions();
			updateAddNewExamState();
		} finally {
			applyingSubjectSnapshot = false;
		}
	}

	/**
	 * Loads all persistence data needed for the initial Exam/Assets presentation of
	 * one Subject without touching JavaFX controls.
	 *
	 * @param subject Working Subject, or {@code null}
	 * @return immutable persistence snapshot
	 * @throws SQLException if the Exam hierarchy cannot be read
	 */
	public SubjectSnapshot loadSubjectSnapshot(Subject subject) throws SQLException {
		return loadSubjectSnapshot(subject, null);
	}

	/**
	 * Reloads the persisted Exams belonging to the current Working Subject.
	 *
	 * @param subject authoritative application Subject, or {@code null}
	 * @throws SQLException if Exam persistence cannot be read
	 */
	public void refresh(Subject subject) throws SQLException {
		Long previousExamId = creatingNewExam || examBox.getValue() == null ? null
				: Long.valueOf(examBox.getValue().getId());
		creatingNewExam = false;
		newExamReturnExamId = null;
		workingSubject = subject;
		restoreNormalExamPresentation();

		// Direct workspace entry remains a synchronous caller for now, but uses the
		// same
		// snapshot contract as asynchronous Working Subject publication.
		applySubjectSnapshot(loadSubjectSnapshot(subject, previousExamId));
	}

	private void applyExamSnapshot(ExamSnapshot snapshot) {
		Exam exam = snapshot.exam();

		// Authoritative Exam metadata also repairs the local suggestion cache
		// introduced
		// by the completed #74 behaviour.
		rememberPersistedExamMetadata(exam);
		stateLabel.setText("State: " + exam.getCaptureState());
		String providerName = exam.getProvider().getName();
		providerField.setValue(providerName);
		providerField.getEditor().setText(providerName);
		if (!yearField.getItems().contains(exam.getYear())) {

			// Historical years remain valid even when outside the standard suggestion
			// window.
			yearField.getItems().add(exam.getYear());
		}
		yearField.setValue(exam.getYear());
		String assessmentName = exam.getName();
		assessmentField.setValue(assessmentName);
		assessmentField.getEditor().setText(assessmentName);
		List<ExamBooklet> booklets = snapshot.booklets().stream().map(BookletSnapshot::booklet).toList();
		refreshBookletNameSuggestions(booklets);
		availableAnswerFiles = snapshot.answerFiles();
		if (snapshot.booklets().isEmpty()) {
			questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		} else {
			List<Node> rows = new ArrayList<>();
			for (BookletSnapshot bookletSnapshot : snapshot.booklets()) {

				// All persistence was completed before publication; row construction is
				// now JavaFX-only.
				rows.add(createQuestionBookletRow(bookletSnapshot.booklet(), bookletSnapshot.assignedAnswerFile()));
			}
			questionBookletsBox.getChildren().setAll(rows);
		}
		if (availableAnswerFiles.isEmpty()) {
			answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));
		} else {
			answerBookletsBox.getChildren()
					.setAll(availableAnswerFiles.stream().map(this::createAnswerFileRow).toList());
		}
		setExamDetailsEditing(false);
	}

	private void beginAnswerBookletAdd() {
		Exam exam = examBox.getValue();
		if (!canBeginAnswerBookletAdd(exam)) {
			return;
		}
		Path sourcePath = answerBookletSourceChooser.get();
		if (sourcePath == null) {

			// Cancelling the native chooser leaves the workspace unchanged.
			return;
		}
		beginAnswerBookletAdd(sourcePath);
	}

	private void beginAnswerBookletAdd(Path sourcePath) {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Exam exam = examBox.getValue();
		if (!canBeginAnswerBookletAdd(exam)) {
			return;
		}
		pendingAnswerBookletEditor = new PendingAnswerBookletEditor(exam, sourcePath);

		// Replace the empty-state label rather than displaying it beside a genuine
		// pending Answer asset.
		if (availableAnswerFiles.isEmpty()) {
			answerBookletsBox.getChildren().clear();
		}
		answerBookletsBox.getChildren().add(pendingAnswerBookletEditor.createRow());

		// A pending structural asset transaction owns the selected Exam until it is
		// either saved or cancelled.
		updateAnswerBookletAddState();

		// Starting an Answer asset also disables the competing Question-asset action.
		updateQuestionBookletAddState();
		refreshQuestionBookletActionStates();
		updateUseSelectedBookletState();
		editExamButton.setDisable(true);
		examBox.setDisable(true);
	}

	private void beginExamDetailsEdit() {
		Exam selectedExam = examBox.getValue();
		if (selectedExam == null) {
			return;
		}

		// Reassert the persisted values directly into the editable ComboBox editors.
		// This prevents suggestion-list state from clearing the metadata when Edit is
		// entered.
		providerField.setValue(selectedExam.getProvider().getName());
		providerField.getEditor().setText(selectedExam.getProvider().getName());
		yearField.setValue(selectedExam.getYear());
		assessmentField.setValue(selectedExam.getName());
		assessmentField.getEditor().setText(selectedExam.getName());
		setExamDetailsEditing(true);
	}

	private void beginNewExam() {
		if (!canBeginNewExam()) {
			return;
		}
		Exam selectedExam = examBox.getValue();
		newExamReturnExamId = selectedExam == null ? null : Long.valueOf(selectedExam.getId());
		creatingNewExam = true;

		// Remove the persisted Exam selection before exposing blank metadata fields.
		// The existing Exam catalogue remains loaded so Cancel can restore the prior
		// row.
		examBox.getSelectionModel().clearSelection();
		clearSelectedExam();
		showNewExamPresentation();
	}

	private void beginQuestionBookletAdd() {
		Exam exam = examBox.getValue();
		if (!canBeginQuestionBookletAdd(exam)) {
			return;
		}
		Path sourcePath = questionBookletSourceChooser.get();
		if (sourcePath == null) {

			// Cancelling native source selection leaves persistence and UI unchanged.
			return;
		}
		beginQuestionBookletAdd(sourcePath);
	}

	private void beginQuestionBookletAdd(Path sourcePath) {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Exam exam = examBox.getValue();
		if (!canBeginQuestionBookletAdd(exam)) {
			return;
		}
		pendingQuestionBookletEditor = new PendingQuestionBookletEditor(exam, sourcePath);
		if (questionBookletEditors.isEmpty()) {

			// Remove the empty-state message before presenting a genuine pending asset.
			questionBookletsBox.getChildren().clear();
		}
		questionBookletsBox.getChildren().add(pendingQuestionBookletEditor.createRow());

		// Only one structural transaction may own the Exam at a time.
		updateQuestionBookletAddState();
		updateAnswerBookletAddState();
		refreshQuestionBookletActionStates();
		updateUseSelectedBookletState();
		editExamButton.setDisable(true);
		examBox.setDisable(true);

		// Open the selected source immediately so the user can inspect the booklet and
		// enter the total Expected Questions while looking at the actual PDF. This is
		// preview-only; the source does not become a persisted Exam asset until Save.
		pendingQuestionBookletPreviewOpen = questionBookletSourcePreviewHandler.test(sourcePath);
	}

	private void buildContent() {
		GridPane examSelectorGrid = new GridPane();
		examSelectorGrid.setHgap(SPACING);
		examSelectorGrid.add(examBox, 0, 0);
		examSelectorGrid.add(stateLabel, 1, 0);
		examSelectorGrid.add(addNewExamButton, 2, 0);
		GridPane.setHgrow(examBox, Priority.ALWAYS);
		VBox examDetails = createExamDetailsSection();
		newExamActionRow = createNewExamActionRow();

		// New Exam transaction controls belong to the Exam they are creating, so keep
		// them inside the same bordered EXAM area as selector and Exam Details.
		VBox examContent = new VBox(ROW_SPACING, examSelectorGrid, examDetails, newExamActionRow);
		VBox examSection = createWorkspaceSection("exam-assets-exam-section", "EXAM", null, examContent);
		VBox questionBooklets = createQuestionBookletsSection();
		VBox answerBooklets = createAnswerBookletsSection();

		// Capture activation remains outside the asset-management sections because it
		// changes the active capture context rather than editing Exam structure.
		getChildren().addAll(examSection, questionBooklets, answerBooklets, new Separator(), useSelectedBookletButton);
	}

	private boolean canBeginAnswerBookletAdd(Exam exam) {

		// Question and Answer asset creation are both structural Exam transactions, so
		// neither may begin while the other is pending.
		return exam != null && !exam.isComplete() && !editingExamDetails && editingBookletEditor == null
				&& pendingAnswerBookletEditor == null && pendingQuestionBookletEditor == null;
	}

	private boolean canBeginNewExam() {

		// New Exam owns the complete Exam/Assets structural workspace and therefore
		// cannot overlap another metadata or asset transaction.
		return workingSubject != null && !creatingNewExam && !editingExamDetails && editingBookletEditor == null
				&& pendingQuestionBookletEditor == null && pendingAnswerBookletEditor == null;
	}

	private boolean canBeginQuestionBookletAdd(Exam exam) {

		// Adding a source booklet changes Exam structure and therefore requires an
		// ACTIVE Exam with no competing structural edit.
		return exam != null && !exam.isComplete() && !editingExamDetails && editingBookletEditor == null
				&& pendingQuestionBookletEditor == null && pendingAnswerBookletEditor == null;
	}

	private void cancelExamDetailsEdit() {
		Exam selectedExam = examBox.getValue();
		if (!editingExamDetails || selectedExam == null) {
			return;
		}

		// Discard staged editor values by reloading the selected persisted Exam. This
		// deliberately stays inside Exam/Assets mode.
		loadSelectedExamSafely(selectedExam);
	}

	private void cancelNewExam() {
		if (!creatingNewExam) {
			return;
		}
		Long returnExamId = newExamReturnExamId;
		creatingNewExam = false;
		newExamReturnExamId = null;
		restoreNormalExamPresentation();
		if (returnExamId != null) {
			Exam returnExam = examBox.getItems().stream().filter(exam -> exam.getId() == returnExamId.longValue())
					.findFirst().orElse(null);
			if (returnExam != null) {

				// Re-selecting through the normal ComboBox listener reconstructs all Exam
				// details and asset rows from authoritative persistence.
				examBox.getSelectionModel().select(returnExam);
				return;
			}
		}

		// No previous Exam existed, or it disappeared while the transaction was open.
		clearSelectedExam();
		updateAddNewExamState();
	}

	private void clearNewExamFields() {
		if (!creatingNewExam) {
			return;
		}
		providerField.getSelectionModel().clearSelection();
		providerField.setValue(null);
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		yearField.setValue(null);
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.setValue(null);
		assessmentField.getEditor().clear();

		// Clearing returns the transaction to its incomplete state without leaving New
		// Exam mode.
		updateNewExamSaveState();
	}

	private void clearSelectedExam() {

		// A pending Answer-booklet draft belongs only to the Exam from which it was
		// started and cannot survive an Exam/Subject refresh.
		boolean closePendingQuestionPreview = pendingQuestionBookletPreviewOpen;

		// Pending structural drafts belong only to the Exam from which they were
		// started and never survive an authoritative workspace reload.
		pendingQuestionBookletEditor = null;
		pendingAnswerBookletEditor = null;
		pendingQuestionBookletPreviewOpen = false;
		if (closePendingQuestionPreview) {

			// PdfWorkspacePane retains the document displayed before VIEWER mode, so
			// cancelling or completing the pending add can restore that context cleanly.
			questionBookletSourcePreviewCloseHandler.run();
		}
		stateLabel.setText("State: —");
		providerField.getSelectionModel().clearSelection();
		providerField.setValue(null);
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		yearField.setValue(null);
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.setValue(null);
		assessmentField.getEditor().clear();

		// Row editors belong to the currently displayed persisted Exam only.
		editingBookletEditor = null;
		questionBookletEditors.clear();
		bookletNameSuggestions = DEFAULT_BOOKLET_NAME_SUGGESTIONS;

		// Answer choices belong only to the Exam currently displayed.
		availableAnswerFiles = List.of();

		// Toggles from the previous Exam must not remain selectable after its visible
		// booklet rows have been discarded.
		questionBookletSelectionGroup.getToggles().clear();
		questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));

		// Recalculate structural actions after clearing all Exam-owned transient state.
		updateQuestionBookletAddState();
		updateAnswerBookletAddState();
		setExamDetailsEditing(false);
		updateUseSelectedBookletState();

		// New Exam availability follows the same cleared transaction state.
		updateAddNewExamState();
	}

	private void configureControls() {
		examBox.setId("exam-assets-exam");
		examBox.setMaxWidth(Double.MAX_VALUE);
		examBox.setConverter(new StringConverter<>() {

			@Override
			public Exam fromString(String text) {

				// Existing Exam selection is always persistence-backed.
				return null;
			}

			@Override
			public String toString(Exam exam) {
				return exam == null ? "" : formatExam(exam);
			}
		});
		examBox.valueProperty().addListener((_, _, exam) -> {

			// Ordinary user selection still loads synchronously. Snapshot publication
			// already contains the selected Exam hierarchy and must not read it twice.
			if (!applyingSubjectSnapshot) {
				loadSelectedExamSafely(exam);
			}
		});
		stateLabel.setId("exam-assets-state");

		// Provider and Assessment use reusable suggestions but their persisted Exam
		// values remain authoritative.
		providerField.setId("exam-assets-provider");
		providerField.setEditable(true);
		providerField.setMaxWidth(Double.MAX_VALUE);
		assessmentField.setId("exam-assets-assessment");
		assessmentField.setEditable(true);
		assessmentField.setMaxWidth(Double.MAX_VALUE);

		// Provider and Assessment suggestions are reusable preferences rather than Exam
		// identity. Their popup rows therefore expose a non-destructive removal action.
		configureSuggestionRemoval(providerField, optionsRepository::removeProvider);
		configureSuggestionRemoval(assessmentField, optionsRepository::removeAssessment);
		yearField.setId("exam-assets-year");
		yearField.setMaxWidth(Double.MAX_VALUE);
		int currentYear = Year.now().getValue();
		for (int year = currentYear; year >= currentYear - YEAR_LOOKBACK_YEARS; year--) {
			yearField.getItems().add(year);
		}

		// Save availability follows semantic metadata changes rather than merely the
		// fact that Edit mode is active.
		providerField.getEditor().textProperty().addListener((_, _, _) -> updateExamMetadataActionStates());
		yearField.valueProperty().addListener((_, _, _) -> updateExamMetadataActionStates());
		assessmentField.getEditor().textProperty().addListener((_, _, _) -> updateExamMetadataActionStates());
		questionBookletsBox.setId("exam-assets-question-booklets");
		answerBookletsBox.setId("exam-assets-answer-booklets");
		editExamButton.setId("exam-assets-edit");
		editExamButton.setOnAction(_ -> beginExamDetailsEdit());
		saveButton.setId("exam-assets-save");
		saveButton.setOnAction(_ -> saveExamDetails());
		cancelButton.setId("exam-assets-cancel");

		// Cancel discards only the staged Exam Details edit. Returning to Capture will
		// later be performed by Use Selected Booklet for Capture.
		cancelButton.setOnAction(_ -> cancelExamDetailsEdit());
		useSelectedBookletButton.setId("exam-assets-use-selected-booklet");
		useSelectedBookletButton.setMinWidth(Region.USE_PREF_SIZE);
		useSelectedBookletButton.setOnAction(_ -> useSelectedQuestionBookletForCapture());
		questionBookletSelectionGroup.selectedToggleProperty().addListener((_, _, _) -> {

			// Capture is available only when one persisted Question booklet is selected
			// and no Exam or booklet edit transaction is currently active.
			updateUseSelectedBookletState();
		});
		addAnswerBookletButton.setId("exam-assets-add-answer-booklet");
		addAnswerBookletButton.setMinWidth(Region.USE_PREF_SIZE);
		addQuestionBookletButton.setId("exam-assets-add-question-booklet");
		addQuestionBookletButton.setMinWidth(Region.USE_PREF_SIZE);

		// Source selection starts one temporary structural Question-booklet
		// transaction.
		addQuestionBookletButton.setOnAction(_ -> beginQuestionBookletAdd());
		updateQuestionBookletAddState();

		// Source selection begins the temporary Answer-booklet editing transaction.
		addAnswerBookletButton.setOnAction(_ -> beginAnswerBookletAdd());
		updateAnswerBookletAddState();
		addNewExamButton.setId("exam-assets-add-new-exam");
		addNewExamButton.setMinWidth(Region.USE_PREF_SIZE);
		addNewExamButton.setOnAction(_ -> beginNewExam());
		clearNewExamButton.setId("exam-assets-new-exam-clear");
		clearNewExamButton.setOnAction(_ -> clearNewExamFields());
		cancelNewExamButton.setId("exam-assets-new-exam-cancel");
		cancelNewExamButton.setOnAction(_ -> cancelNewExam());
		saveNewExamButton.setId("exam-assets-new-exam-save");
		saveNewExamButton.setOnAction(_ -> saveNewExam());

		// New Exam cannot begin until Exam/Assets has been refreshed with an
		// authoritative Working Subject.
		updateAddNewExamState();
		updateNewExamSaveState();
		refreshMetadataOptions();
		clearSelectedExam();
	}

	private void configureSuggestionRemoval(ComboBox<String> field, Consumer<String> removalAction) {
		if (field == null) {
			throw new NullPointerException("field");
		}
		if (removalAction == null) {
			throw new NullPointerException("removalAction");
		}
		MenuItem removeItem = new MenuItem("Remove from suggestions");
		ContextMenu contextMenu = new ContextMenu(removeItem);
		contextMenu.setOnShowing(_ -> {
			String value = field.getEditor().getText().strip();

			// Only an actual persisted suggestion may be removed. Free text entered by
			// the user must never acquire suggestion-removal semantics accidentally.
			boolean storedSuggestion = field.getItems().stream().anyMatch(existing -> existing.equalsIgnoreCase(value));
			removeItem.setDisable(!storedSuggestion);
		});
		removeItem.setOnAction(_ -> {
			String value = field.getEditor().getText().strip();
			boolean storedSuggestion = field.getItems().stream().anyMatch(existing -> existing.equalsIgnoreCase(value));
			if (!storedSuggestion) {
				return;
			}

			// This action modifies only the reusable suggestion preference. Persisted Exam
			// metadata remains authoritative and unchanged.
			removalAction.accept(value);
			refreshMetadataOptions();
		});
		EventHandler<ContextMenuEvent> showRemovalMenu = event -> {

			// Editable ComboBoxes may route a secondary click either to the
			// ComboBox itself or to its TextField editor. Handle both explicitly.
			if (contextMenu.isShowing()) {
				contextMenu.hide();
			}
			Node anchor = event.getSource() instanceof Node node ? node : field;
			contextMenu.show(anchor, event.getScreenX(), event.getScreenY());
			event.consume();
		};

		// Expose the same semantic action through both parts of the editable control.
		field.setContextMenu(contextMenu);
		field.getEditor().setContextMenu(contextMenu);

		// Explicit handling avoids depending on JavaFX skin-specific context-menu
		// dispatch, which differs between desktop and Xvfb.
		field.setOnContextMenuRequested(showRemovalMenu);
		field.getEditor().setOnContextMenuRequested(showRemovalMenu);
	}

	private VBox createAnswerBookletsSection() {

		// The Add action creates an asset owned by this section, so present it directly
		// beside the ANSWER BOOKLETS heading instead of consuming another row.
		return createWorkspaceSection("exam-assets-answer-section", "ANSWER BOOKLETS", addAnswerBookletButton,
				answerBookletsBox);
	}

	private VBox createAnswerFileRow(AnswerFile answerFile) {
		Label heading = new Label(answerFile.getName());
		heading.setStyle(HEADING_STYLE);
		Label source = new Label(sourceFileName(answerFile.getSourceDocument().getRelativePath()));
		Region sourceSpacer = new Region();
		HBox.setHgrow(sourceSpacer, Priority.ALWAYS);
		Button viewButton = new Button("View");
		viewButton.setId("exam-assets-answer-view-" + answerFile.getId());
		viewButton.setMinWidth(Region.USE_PREF_SIZE);

		// Answer booklet inspection remains independent of Question/Answer capture.
		viewButton.setOnAction(_ -> answerFileViewHandler.accept(answerFile));
		HBox sourceRow = new HBox(SPACING, source, sourceSpacer, viewButton);
		CheckBox explanations = new CheckBox("Contains answer explanations");
		explanations.setId("exam-assets-answer-explanations-" + answerFile.getId());

		// The checkbox always reflects authoritative persisted AnswerFile metadata
		// when the row is constructed.
		explanations.setSelected(answerFile.hasAnswerExplanations());

		// Explanation presence is descriptive AnswerFile metadata rather than Exam
		// structure, so it can be corrected directly without entering a structural
		// Exam or booklet edit transaction.
		explanations.setOnAction(_ -> updateAnswerFileExplanationMetadata(answerFile, explanations));
		VBox row = new VBox(ROW_SPACING, heading, sourceRow, explanations);
		row.setId("exam-assets-answer-file-" + answerFile.getId());
		row.setPadding(SECTION_PADDING);
		row.setStyle(BORDER_STYLE);
		return row;
	}

	private VBox createExamDetailsSection() {
		GridPane details = new GridPane();
		details.setHgap(SPACING);
		details.setVgap(ROW_SPACING);
		details.addRow(0, new Label("Provider"), providerField);
		details.addRow(1, new Label("Year"), yearField);
		details.addRow(2, new Label("Assessment"), assessmentField);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(yearField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		Region actionSpacer = new Region();
		HBox.setHgrow(actionSpacer, Priority.ALWAYS);

		// Persisted Exam correction retains its existing Edit / Cancel / Save
		// transaction. New Exam mode temporarily hides this complete action row.
		examDetailsActionRow = new HBox(SPACING, actionSpacer, editExamButton, cancelButton, saveButton);
		examDetailsActionRow.setId("exam-assets-details-actions");
		VBox section = createSection("Exam Details", new VBox(ROW_SPACING, details, examDetailsActionRow));
		section.setId("exam-assets-details-section");
		return section;
	}

	private HBox createNewExamActionRow() {
		Region actionSpacer = new Region();
		HBox.setHgrow(actionSpacer, Priority.ALWAYS);

		// Clear remains separate on the left while Cancel and Save Exam form the
		// transaction-completion controls on the right.
		HBox actionRow = new HBox(SPACING, clearNewExamButton, actionSpacer, cancelNewExamButton, saveNewExamButton);
		actionRow.setId("exam-assets-new-exam-actions");

		// Normal Exam browsing is the initial workspace state.
		actionRow.setVisible(false);
		actionRow.setManaged(false);
		return actionRow;
	}

	private VBox createQuestionBookletRow(ExamBooklet booklet, AnswerFile assignedAnswerFile) {

		// Each editor receives the persisted Answer assignment separately from the
		// list of Answer assets that may be chosen.
		QuestionBookletEditor editor = new QuestionBookletEditor(booklet, assignedAnswerFile);
		questionBookletEditors.add(editor);
		return editor.createRow();
	}

	private VBox createQuestionBookletsSection() {

		// The Add action creates an asset owned by this section, so present it directly
		// beside the QUESTION BOOKLETS heading instead of consuming another row.
		return createWorkspaceSection("exam-assets-question-section", "QUESTION BOOKLETS", addQuestionBookletButton,
				questionBookletsBox);
	}

	private VBox createSection(String headingText, javafx.scene.Node content) {
		Label heading = new Label(headingText);
		heading.setStyle(HEADING_STYLE);
		VBox section = new VBox(ROW_SPACING, heading, content);
		return section;
	}

	private VBox createWorkspaceSection(String id, String headingText, Node headingAction, Node content) {
		if (id == null) {
			throw new NullPointerException("id");
		}
		if (headingText == null) {
			throw new NullPointerException("headingText");
		}
		if (content == null) {
			throw new NullPointerException("content");
		}
		Label heading = new Label(headingText);
		heading.setId(id + "-heading");
		heading.setStyle(HEADING_STYLE);
		Region headingSpacer = new Region();
		HBox.setHgrow(headingSpacer, Priority.ALWAYS);
		HBox headingRow = new HBox(SPACING, heading, headingSpacer);
		headingRow.setId(id + "-heading-row");

		// Section-level actions sit on the same visual row as their section heading
		// rather than consuming a separate row beneath the section contents.
		if (headingAction != null) {
			headingRow.getChildren().add(headingAction);
		}

		// Major Exam/Assets areas need one explicit visual boundary around their
		// heading, section action and all controls owned by that area.
		VBox section = new VBox(ROW_SPACING, headingRow, content);
		section.setId(id);
		section.setPadding(SECTION_PADDING);
		section.setStyle(WORKSPACE_SECTION_STYLE);
		return section;
	}

	private String defaultAnswerBookletName(Path sourcePath) {
		Path filenamePath = sourcePath.getFileName();
		if (filenamePath == null) {
			return "";
		}
		String filename = filenamePath.toString();
		int extensionSeparator = filename.lastIndexOf('.');
		if (extensionSeparator <= 0) {

			// A filename without an extension is already the best available default
			// descriptive name.
			return filename;
		}

		// Strip only the final extension; retain any earlier periods as part of the
		// original asset name.
		return filename.substring(0, extensionSeparator);
	}

	private String defaultQuestionBookletName(Path sourcePath) {
		Path filenamePath = sourcePath.getFileName();
		if (filenamePath == null) {
			return "";
		}
		String filename = filenamePath.toString();
		int extensionSeparator = filename.lastIndexOf('.');
		if (extensionSeparator <= 0) {

			// A filename without an extension is already the best available initial name.
			return filename;
		}

		// Strip only the final extension so meaningful periods inside the filename are
		// retained.
		return filename.substring(0, extensionSeparator);
	}

	private String editedText(ComboBox<String> field) {

		// Editable ComboBoxes keep newly typed values in their editor until committed,
		// so read the editor rather than assuming getValue() has already changed.
		return field.getEditor().getText().strip();
	}

	private String formatExam(Exam exam) {

		// Match the agreed compact selector presentation: year, provider and
		// assessment identify one persisted Exam without repeating Subject.
		return "%d %s %s".formatted(exam.getYear(), exam.getProvider().getName(), exam.getName());
	}

	private String formatQuestionFormat(ExamBookletQuestionFormat format) {
		return switch (format) {
		case MULTIPLE_CHOICE -> "MCQ";
		case WRITTEN_RESPONSE -> "Written Response";
		case MIXED -> "Both";
		case UNSPECIFIED -> "Not recorded";
		};
	}

	private boolean isExamDetailsDirty() {
		Exam persistedExam = examBox.getValue();
		if (!editingExamDetails || persistedExam == null) {
			return false;
		}

		// Compare the proposed editor values with the persisted Exam that was selected
		// when editing began. Outer whitespace is not a meaningful metadata change.
		String providerName = editedText(providerField);
		Integer year = yearField.getValue();
		String assessmentName = editedText(assessmentField);
		return !providerName.equals(persistedExam.getProvider().getName()) || year == null
				|| year.intValue() != persistedExam.getYear() || !assessmentName.equals(persistedExam.getName());
	}

	private boolean isNewExamDetailsComplete() {

		// No persisted Exam can be created without its inherited Subject and complete
		// Provider / Year / Assessment identity.
		return workingSubject != null && !editedText(providerField).isBlank() && yearField.getValue() != null
				&& !editedText(assessmentField).isBlank();
	}

	private ExamSnapshot loadExamSnapshot(Exam exam) throws SQLException {
		List<ExamBooklet> booklets = List.copyOf(examWriter.findExamBooklets(exam));
		List<AnswerFile> answerFiles = List.copyOf(answerWriter.findAnswerFiles(exam));
		List<BookletSnapshot> bookletSnapshots = new ArrayList<>();
		for (ExamBooklet booklet : booklets) {

			// Resolve every assignment on the worker thread so JavaFX publication becomes
			// presentation-only.
			bookletSnapshots.add(new BookletSnapshot(booklet, answerWriter.findAnswerFile(booklet)));
		}
		return new ExamSnapshot(exam, bookletSnapshots, answerFiles);
	}

	private void loadSelectedExam(Exam exam) throws SQLException {
		clearSelectedExam();
		if (exam == null) {
			return;
		}

		// Ordinary user selection retains its existing synchronous persistence path.
		// Subject transitions instead preload this same immutable structure off-thread.
		applyExamSnapshot(loadExamSnapshot(exam));
	}

	private void loadSelectedExamSafely(Exam exam) {
		try {
			loadSelectedExam(exam);
		} catch (SQLException exception) {
			clearSelectedExam();

			// A persistence failure must not leave stale Exam or asset information
			// visible as though it were authoritative.
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.setTitle("Exam / Assets");
			alert.setHeaderText("Exam assets could not be loaded.");
			alert.setContentText(exception.getMessage());
			alert.showAndWait();
		}
	}

	private SubjectSnapshot loadSubjectSnapshot(Subject subject, Long preferredExamId) throws SQLException {
		if (subject == null) {

			// Clearing Working Subject requires no persistence lookup.
			return new SubjectSnapshot(null, List.of(), null);
		}
		List<Exam> exams = List.copyOf(examWriter.findExamsForSubject(subject));
		if (exams.isEmpty()) {
			return new SubjectSnapshot(subject, exams, null);
		}
		Exam selectedExam = null;
		if (preferredExamId != null) {

			// Preserve an existing selection when refreshing the same Subject.
			selectedExam = exams.stream().filter(exam -> exam.getId() == preferredExamId.longValue()).findFirst()
					.orElse(null);
		}
		if (selectedExam == null) {

			// Repository ordering already identifies the preferred initial Exam.
			selectedExam = exams.getFirst();
		}
		return new SubjectSnapshot(subject, exams, loadExamSnapshot(selectedExam));
	}

	private void refreshBookletNameSuggestions(List<ExamBooklet> booklets) {
		LinkedHashSet<String> suggestions = new LinkedHashSet<>(DEFAULT_BOOKLET_NAME_SUGGESTIONS);

		// Persisted custom labels become reusable suggestions for other booklets in
		// this Exam without restricting the user to a fixed vocabulary.
		for (ExamBooklet booklet : booklets) {
			suggestions.add(booklet.getName());
		}
		bookletNameSuggestions = List.copyOf(suggestions);
	}

	private void refreshMetadataOptions() {
		String providerValue = providerField.getValue();
		String providerText = providerField.getEditor().getText();
		String assessmentValue = assessmentField.getValue();
		String assessmentText = assessmentField.getEditor().getText();

		// Preferences supply suggestions only. Replacing their item lists must not
		// replace persisted Exam metadata or staged editor text.
		providerField.getItems().setAll(optionsRepository.getProviders());
		assessmentField.getItems().setAll(optionsRepository.getAssessments());
		if (providerValue != null) {
			providerField.setValue(providerValue);
		}
		providerField.getEditor().setText(providerText);
		if (assessmentValue != null) {
			assessmentField.setValue(assessmentValue);
		}
		assessmentField.getEditor().setText(assessmentText);
	}

	private void refreshQuestionBookletActionStates() {

		// Row-level action availability depends on whether another structural edit is
		// currently active, so refresh all visible editors together.
		for (QuestionBookletEditor editor : questionBookletEditors) {
			editor.updateActionState();
		}

		// New Exam is another structural workspace operation and follows the same
		// transaction exclusion rules.
		updateAddNewExamState();
	}

	private void reloadSubjectExams(Subject subject, Long preferredExamId) throws SQLException {
		examBox.getSelectionModel().clearSelection();
		examBox.getItems().clear();
		clearSelectedExam();

		// Suggestions are user preferences rather than Exam identity and may have
		// changed
		// after creating or correcting another Exam.
		refreshMetadataOptions();
		if (subject == null) {
			updateAddNewExamState();
			return;
		}
		List<Exam> exams = examWriter.findExamsForSubject(subject);
		examBox.getItems().setAll(exams);
		if (exams.isEmpty()) {
			updateAddNewExamState();
			return;
		}
		Exam selectedExam = null;
		if (preferredExamId != null) {

			// A newly created or previously selected Exam wins over repository ordering.
			selectedExam = exams.stream().filter(exam -> exam.getId() == preferredExamId.longValue()).findFirst()
					.orElse(null);
		}
		if (selectedExam == null) {

			// Repository ordering already presents the newest available Exam first.
			selectedExam = exams.getFirst();
		}
		examBox.getSelectionModel().select(selectedExam);
		updateAddNewExamState();
	}

	private void rememberPersistedExamMetadata(Exam exam) {
		String providerName = exam.getProvider().getName();
		String assessmentName = exam.getName();
		boolean providerMissing = optionsRepository.getProviders().stream()
				.noneMatch(existing -> existing.equalsIgnoreCase(providerName));
		boolean assessmentMissing = optionsRepository.getAssessments().stream()
				.noneMatch(existing -> existing.equalsIgnoreCase(assessmentName));
		if (!providerMissing && !assessmentMissing) {

			// Avoid rebuilding the ComboBox item lists when the local cache already
			// contains both authoritative values.
			return;
		}
		if (providerMissing) {

			// Encountering authoritative persisted metadata makes it reusable locally.
			optionsRepository.addProvider(providerName);
		}
		if (assessmentMissing) {

			// Assessment values follow the same local-cache rule as Provider values.
			optionsRepository.addAssessment(assessmentName);
		}

		// Make newly discovered authoritative values available to the ComboBoxes during
		// this same Exam load rather than waiting for another workspace refresh.
		refreshMetadataOptions();
	}

	private void restoreNormalExamPresentation() {

		// The normal persisted-Exam editing controls replace the New Exam transaction
		// controls whenever creation ends or the Working Subject changes.
		if (examDetailsActionRow != null) {
			examDetailsActionRow.setVisible(true);
			examDetailsActionRow.setManaged(true);
		}
		if (newExamActionRow != null) {
			newExamActionRow.setVisible(false);
			newExamActionRow.setManaged(false);
		}
		examBox.setDisable(false);
		updateAddNewExamState();
	}

	private void saveExamDetails() {
		Exam selectedExam = examBox.getValue();
		if (!editingExamDetails || selectedExam == null) {
			return;
		}
		String providerName = editedText(providerField);
		Integer year = yearField.getValue();
		String assessmentName = editedText(assessmentField);
		if (providerName.isBlank() || year == null || assessmentName.isBlank()) {
			showCorrectionError("Complete Provider, Year and Assessment before saving.");
			return;
		}
		try {
			Exam corrected = correctionHandler.correct(selectedExam, providerName, year.intValue(), assessmentName);

			// Retain useful suggestions independently of the Exam identity itself.
			optionsRepository.addProvider(corrected.getProvider().getName());
			optionsRepository.addAssessment(corrected.getName());

			// A fresh repository read proves the workspace is showing authoritative
			// persisted values rather than only the object returned by the correction.
			refresh(corrected.getSubject());
			setExamDetailsEditing(false);
		} catch (SQLException | IOException | IllegalArgumentException | IllegalStateException exception) {
			showCorrectionError(exception.getMessage());
		}
	}

	private void saveNewExam() {
		if (!creatingNewExam || !isNewExamDetailsComplete()) {
			return;
		}
		String providerName = editedText(providerField);
		Integer year = yearField.getValue();
		String assessmentName = editedText(assessmentField);
		try {
			Exam created = examWriter.createExam(workingSubject, providerName, year.intValue(), assessmentName);

			// Successful manual entry also enriches the reusable suggestions presented by
			// subsequent Exam creation and correction workflows.
			optionsRepository.addProvider(created.getProvider().getName());
			optionsRepository.addAssessment(created.getName());
			long createdExamId = created.getId();
			creatingNewExam = false;
			newExamReturnExamId = null;
			restoreNormalExamPresentation();

			// Reload from SQLite rather than treating the returned object as sufficient
			// proof of authoritative workspace state.
			reloadSubjectExams(workingSubject, Long.valueOf(createdExamId));
		} catch (SQLException | IllegalArgumentException exception) {

			// Failed creation leaves all entered values staged so the user can correct the
			// metadata rather than re-entering the transaction.
			showNewExamError(exception.getMessage());
		}
	}

	private void selectQuestionBooklet(long bookletId) {
		for (Toggle toggle : questionBookletSelectionGroup.getToggles()) {
			Object userData = toggle.getUserData();
			if (userData instanceof ExamBooklet booklet && booklet.getId() == bookletId) {

				// Selection identifies the newly added row for the later explicit capture
				// transition; it does not itself activate the booklet.
				questionBookletSelectionGroup.selectToggle(toggle);
				updateUseSelectedBookletState();
				return;
			}
		}
	}

	private void setExamDetailsEditing(boolean editing) {
		editingExamDetails = editing;

		// Metadata remains visible at all times and becomes mutable only after the
		// explicit Edit action.
		providerField.setDisable(!editing);
		yearField.setDisable(!editing);
		assessmentField.setDisable(!editing);
		editExamButton.setDisable(editing || examBox.getValue() == null);

		// Cancel remains available throughout an edit. Save requires an actual change.
		cancelButton.setDisable(!editing);
		updateExamDetailsSaveState();

		// Do not allow another Exam to be selected while unsaved metadata is staged.
		examBox.setDisable(editing);

		// Exam Details and booklet structural edits are mutually exclusive
		// transactions.
		refreshQuestionBookletActionStates();

		// Capture transition must not silently discard a staged Exam Details edit.
		updateUseSelectedBookletState();

		// Exam Details, Question-booklet creation and Answer-booklet creation are
		// mutually exclusive transactions.
		updateQuestionBookletAddState();
		updateAnswerBookletAddState();
	}

	private void showAnswerFileError(String header, String message) {

		// AnswerFile metadata errors belong to the asset being edited rather than the
		// Exam Details or Question-booklet structural transactions.
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam / Assets");
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showBookletError(String message) {

		// A failed save leaves the row in Edit mode so the proposed values can be
		// corrected without having to re-enter the transaction.
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam / Assets");
		alert.setHeaderText("Question booklet metadata could not be saved.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showCorrectionError(String message) {

		// Keep the user in edit mode after failure so the proposed correction can be
		// adjusted without re-entering the workflow.
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam / Assets");
		alert.setHeaderText("Exam metadata could not be saved.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showNewExamError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam / Assets");
		alert.setHeaderText("The new Exam could not be saved.");
		alert.setContentText(message == null || message.isBlank() ? "Exam creation failed." : message);

		// A failed Save keeps the New Exam transaction active so entered metadata can
		// be
		// corrected without starting again.
		alert.showAndWait();
	}

	private void showNewExamPresentation() {
		stateLabel.setText("NEW EXAM");
		clearNewExamFields();

		// Provider, Year and Assessment are the only Exam identity values entered here.
		// Subject remains inherited from the permanent Working Subject control.
		providerField.setDisable(false);
		yearField.setDisable(false);
		assessmentField.setDisable(false);
		examBox.setDisable(true);
		addNewExamButton.setDisable(true);
		examDetailsActionRow.setVisible(false);
		examDetailsActionRow.setManaged(false);
		newExamActionRow.setVisible(true);
		newExamActionRow.setManaged(true);
		questionBookletsBox.getChildren().setAll(new Label("Save the Exam before adding Question booklets."));
		answerBookletsBox.getChildren().setAll(new Label("Save the Exam before adding Answer booklets."));

		// Assets require a persisted Exam identity, so neither creation action can run
		// during the unsaved New Exam transaction.
		addQuestionBookletButton.setDisable(true);
		addAnswerBookletButton.setDisable(true);
		useSelectedBookletButton.setDisable(true);
		updateNewExamSaveState();
	}

	private String sourceFileName(String relativePath) {
		Path path = Path.of(relativePath);
		Path fileName = path.getFileName();

		// Defensive fallback keeps a malformed-but-readable path visible rather than
		// presenting an empty source label.
		return fileName == null ? relativePath : fileName.toString();
	}

	private void updateAddNewExamState() {

		// The action remains visible but unavailable whenever another structural
		// transaction currently owns the Exam/Assets workspace.
		addNewExamButton.setDisable(!canBeginNewExam());
	}

	private void updateAnswerBookletAddState() {
		Exam exam = examBox.getValue();

		// COMPLETE Exams remain inspectable but cannot acquire additional structural
		// assets until explicitly reactivated.
		addAnswerBookletButton.setDisable(!canBeginAnswerBookletAdd(exam));
	}

	private void updateAnswerFileExplanationMetadata(AnswerFile answerFile, CheckBox explanations) {
		boolean persistedValue = answerFile.hasAnswerExplanations();
		boolean requestedValue = explanations.isSelected();
		if (requestedValue == persistedValue) {

			// No persistence work is required when the control already represents the
			// AnswerFile value from which this row was constructed.
			return;
		}
		try {
			answerWriter.setContainsAnswerExplanations(answerFile, requestedValue);
		} catch (SQLException | IllegalArgumentException exception) {

			// A failed write must restore the checkbox to the authoritative value rather
			// than leave unsaved metadata presented as though it were current.
			explanations.setSelected(persistedValue);
			showAnswerFileError("Answer explanation metadata could not be saved.", exception.getMessage());
		}
	}

	private void updateExamDetailsSaveState() {

		// Save represents a real persistence operation, so it remains unavailable
		// until the staged Exam Details differ from authoritative persistence.
		saveButton.setDisable(!isExamDetailsDirty());
	}

	private void updateExamMetadataActionStates() {

		// The same metadata fields serve existing-Exam correction and New Exam
		// creation, but each transaction has independent Save rules.
		updateExamDetailsSaveState();
		updateNewExamSaveState();
	}

	private void updateNewExamSaveState() {

		// Save Exam becomes available only while the New Exam transaction is active and
		// every required identity field is complete.
		saveNewExamButton.setDisable(!creatingNewExam || !isNewExamDetailsComplete());
	}

	private void updateQuestionBookletAddState() {

		// Button availability is derived from the complete current transaction state.
		addQuestionBookletButton.setDisable(!canBeginQuestionBookletAdd(examBox.getValue()));
	}

	private void updateUseSelectedBookletState() {

		// Capture activation must never abandon staged Exam, Question-booklet or
		// Answer-booklet structural work.
		useSelectedBookletButton.setDisable(creatingNewExam || editingExamDetails || editingBookletEditor != null
				|| pendingQuestionBookletEditor != null || pendingAnswerBookletEditor != null
				|| questionBookletSelectionGroup.getSelectedToggle() == null);
	}

	private void useSelectedQuestionBookletForCapture() {
		Toggle selectedToggle = questionBookletSelectionGroup.getSelectedToggle();
		if (selectedToggle == null) {
			return;
		}
		Object selectedValue = selectedToggle.getUserData();
		if (!(selectedValue instanceof ExamBooklet booklet)) {
			throw new IllegalStateException("Selected Question booklet has no persisted ExamBooklet");
		}

		// Application code owns the actual PDF/capture transition. This pane only
		// publishes which authoritative booklet the user selected.
		captureBookletHandler.accept(booklet);
	}

	/**
	 * Imports and persists one new Answer booklet selected through Exam/Assets.
	 */
	@FunctionalInterface
	public interface AnswerBookletCreationHandler {

		/**
		 * Creates one AnswerFile for an existing Exam.
		 *
		 * @param exam                       owning Exam
		 * @param sourcePath                 user-selected PDF source
		 * @param name                       Answer booklet name
		 * @param containsAnswerExplanations whether explanations are present
		 * @return persisted AnswerFile
		 * @throws IOException  if the source cannot be imported or hashed
		 * @throws SQLException if persistence fails
		 */
		AnswerFile create(Exam exam, Path sourcePath, String name, boolean containsAnswerExplanations)
				throws IOException, SQLException;
	}

	/**
	 * Persistence snapshot for one Question booklet and its current AnswerFile
	 * assignment.
	 *
	 * @param booklet            persisted Question booklet
	 * @param assignedAnswerFile currently assigned AnswerFile, or {@code null}
	 */
	public record BookletSnapshot(ExamBooklet booklet, AnswerFile assignedAnswerFile) {

		/**
		 * Validates the immutable booklet snapshot.
		 */
		public BookletSnapshot {
			if (booklet == null) {
				throw new NullPointerException("booklet");
			}
		}
	}

	/**
	 * Performs one authoritative Exam metadata correction.
	 */
	@FunctionalInterface
	public interface ExamCorrectionHandler {

		/**
		 * Corrects one persisted Exam.
		 *
		 * @param exam           Exam being corrected
		 * @param providerName   corrected provider
		 * @param year           corrected year
		 * @param assessmentName corrected assessment name
		 * @return corrected Exam with the same persistent identity
		 * @throws SQLException if persistence fails
		 * @throws IOException  if managed PDF relocation fails
		 */
		Exam correct(Exam exam, String providerName, int year, String assessmentName) throws SQLException, IOException;
	}

	/**
	 * Persistence snapshot for the Exam currently selected in Exam/Assets.
	 *
	 * @param exam        persisted Exam
	 * @param booklets    Question booklets and their AnswerFile assignments
	 * @param answerFiles AnswerFiles owned by the Exam
	 */
	public record ExamSnapshot(Exam exam, List<BookletSnapshot> booklets, List<AnswerFile> answerFiles) {

		/**
		 * Validates and freezes the selected Exam snapshot.
		 */
		public ExamSnapshot {
			if (exam == null) {
				throw new NullPointerException("exam");
			}
			if (booklets == null) {
				throw new NullPointerException("booklets");
			}
			if (answerFiles == null) {
				throw new NullPointerException("answerFiles");
			}

			// Worker-thread persistence results must become stable immutable input before
			// they are published back to JavaFX.
			booklets = List.copyOf(booklets);
			answerFiles = List.copyOf(answerFiles);
		}
	}

	/**
	 * Imports and persists one new Question booklet selected through Exam/Assets.
	 */
	@FunctionalInterface
	public interface QuestionBookletCreationHandler {

		/**
		 * Creates one Question booklet for an existing Exam.
		 *
		 * @param exam                  owning Exam
		 * @param sourcePath            user-selected source PDF
		 * @param name                  booklet name
		 * @param questionFormat        explicit Question format
		 * @param expectedQuestionCount expected top-level Question count, or
		 *                              {@code null} until reviewed
		 * @return persisted Question booklet
		 * @throws IOException  if the source cannot be imported or hashed
		 * @throws SQLException if persistence fails
		 */
		ExamBooklet create(Exam exam, Path sourcePath, String name, ExamBookletQuestionFormat questionFormat,
				Integer expectedQuestionCount) throws IOException, SQLException;
	}

	/**
	 * Persistence snapshot required to present Exam/Assets for one Working Subject.
	 *
	 * @param subject      authoritative Working Subject, or {@code null} when
	 *                     cleared
	 * @param exams        Exams belonging to that Subject
	 * @param selectedExam complete snapshot for the Exam to display, or
	 *                     {@code null}
	 */
	public record SubjectSnapshot(Subject subject, List<Exam> exams, ExamSnapshot selectedExam) {

		/**
		 * Validates and freezes the Subject snapshot.
		 */
		public SubjectSnapshot {
			if (exams == null) {
				throw new NullPointerException("exams");
			}

			// Do not expose a mutable repository result across the worker/FX boundary.
			exams = List.copyOf(exams);
		}
	}

	private final class PendingQuestionBookletEditor {

		private final Exam exam;
		private final Path sourcePath;
		private final ComboBox<String> nameField = new ComboBox<>();
		private final ToggleGroup formatGroup = new ToggleGroup();
		private final RadioButton mcqButton = new RadioButton("MCQ");
		private final RadioButton writtenButton = new RadioButton("Written Response");
		private final RadioButton bothButton = new RadioButton("Both");
		private final TextField expectedField = new TextField();
		private final Button cancelButton = new Button("Cancel");
		private final Button saveButton = new Button("Save");

		private PendingQuestionBookletEditor(Exam exam, Path sourcePath) {
			this.exam = exam;
			this.sourcePath = sourcePath;
			configureControls();
		}

		private void cancel() {

			// Nothing has been persisted yet; restore the selected Exam directly from
			// authoritative storage.
			pendingQuestionBookletEditor = null;
			loadSelectedExamSafely(exam);
		}

		private void configureControls() {
			nameField.setId("exam-assets-new-question-name");
			nameField.setEditable(true);
			nameField.setMaxWidth(Double.MAX_VALUE);
			nameField.getItems().setAll(bookletNameSuggestions);
			String defaultName = defaultQuestionBookletName(sourcePath);
			nameField.setValue(defaultName);
			nameField.getEditor().setText(defaultName);
			mcqButton.setId("exam-assets-new-question-format-mcq");
			writtenButton.setId("exam-assets-new-question-format-written");
			bothButton.setId("exam-assets-new-question-format-both");
			mcqButton.setToggleGroup(formatGroup);
			writtenButton.setToggleGroup(formatGroup);
			bothButton.setToggleGroup(formatGroup);

			// Toggle user data is the exact enum persisted by the importer.
			mcqButton.setUserData(ExamBookletQuestionFormat.MULTIPLE_CHOICE);
			writtenButton.setUserData(ExamBookletQuestionFormat.WRITTEN_RESPONSE);
			bothButton.setUserData(ExamBookletQuestionFormat.MIXED);
			expectedField.setId("exam-assets-new-question-expected");
			expectedField.setPrefColumnCount(2);
			expectedField.setMaxWidth(Region.USE_PREF_SIZE);
			cancelButton.setId("exam-assets-new-question-cancel");
			saveButton.setId("exam-assets-new-question-save");
			cancelButton.setOnAction(_ -> cancel());
			saveButton.setOnAction(_ -> save());

			// Any staged metadata change can affect whether the new booklet is valid for
			// persistence.
			nameField.getEditor().textProperty().addListener((_, _, _) -> updateSaveState());
			formatGroup.selectedToggleProperty().addListener((_, _, _) -> updateSaveState());
			expectedField.textProperty().addListener((_, _, _) -> updateSaveState());
			updateSaveState();
		}

		private VBox createRow() {
			Label heading = new Label("New Question Booklet");
			heading.setStyle(HEADING_STYLE);
			Label sourceLabel = new Label(sourceFileName(sourcePath.toString()));
			sourceLabel.setId("exam-assets-new-question-source");
			HBox formatControls = new HBox(SPACING, mcqButton, writtenButton, bothButton);
			formatControls.setId("exam-assets-new-question-format-row");
			GridPane metadata = new GridPane();
			metadata.setHgap(SPACING);
			metadata.setVgap(ROW_SPACING);
			metadata.addRow(0, new Label("Name"), nameField);
			metadata.addRow(1, new Label("Type"), formatControls);
			metadata.addRow(2, new Label("Expected Questions"), expectedField);
			GridPane.setHgrow(nameField, Priority.ALWAYS);
			Region actionSpacer = new Region();
			HBox.setHgrow(actionSpacer, Priority.ALWAYS);
			HBox actions = new HBox(SPACING, actionSpacer, cancelButton, saveButton);

			// Expected count may remain blank until inspection establishes the
			// authoritative top-level Question count.
			VBox row = new VBox(ROW_SPACING, heading, sourceLabel, metadata, actions);
			row.setId("exam-assets-new-question-booklet");
			row.setPadding(SECTION_PADDING);
			row.setStyle(BORDER_STYLE);
			return row;
		}

		private Integer expectedQuestionCount() {
			String text = expectedField.getText().strip();
			if (text.isBlank()) {

				// Blank deliberately means the booklet still requires source review.
				return null;
			}
			try {
				int count = Integer.parseInt(text);
				if (count < 1 || count > 99) {
					throw new IllegalArgumentException("Expected Questions must be between 1 and 99 when supplied.");
				}
				return count;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Expected Questions must be a positive whole number.");
			}
		}

		private boolean hasValidValues() {
			if (nameField.getEditor().getText().strip().isBlank() || selectedFormat() == null) {
				return false;
			}
			try {
				expectedQuestionCount();
				return true;
			} catch (IllegalArgumentException exception) {

				// Invalid staged numeric input remains visible but cannot be saved.
				return false;
			}
		}

		private void save() {
			if (saveButton.isDisabled()) {
				return;
			}
			try {
				ExamBooklet created = questionBookletCreationHandler.create(exam, sourcePath,
						nameField.getEditor().getText().strip(), selectedFormat(), expectedQuestionCount());
				pendingQuestionBookletEditor = null;

				// Reload first so the workspace row and all Answer-assignment choices come
				// from persistence rather than from the returned object alone.
				loadSelectedExamSafely(exam);
				selectQuestionBooklet(created.getId());

				// A newly persisted booklet immediately enters read-only inspection of the
				// authoritative managed PDF. This is not capture activation.
				questionBookletViewHandler.accept(created);
			} catch (IOException | SQLException | IllegalArgumentException | IllegalStateException exception) {
				String message = exception.getMessage();
				if (message == null || message.isBlank()) {
					message = exception.getClass().getSimpleName();
				}

				// Leave the staged editor intact so the user can correct metadata or cancel.
				showBookletError(message);
			}
		}

		private ExamBookletQuestionFormat selectedFormat() {
			Toggle selected = formatGroup.getSelectedToggle();
			if (selected == null) {
				return null;
			}

			// Every Type toggle carries the exact persisted enum value.
			return (ExamBookletQuestionFormat) selected.getUserData();
		}

		private void updateSaveState() {

			// Source selection is already complete; Save depends only on valid structural
			// metadata.
			saveButton.setDisable(!hasValidValues());
		}
	}

	// Keep a real object for the No Answer Booklet choice so selection is distinct
	// from an uninitialised ComboBox value.
	private record AnswerAssignmentChoice(AnswerFile answerFile, String label) {

		@Override
		public String toString() {
			return label;
		}
	}

	private final class PendingAnswerBookletEditor {

		private final Exam exam;
		private final Path sourcePath;
		private final TextField nameField = new TextField();
		private final CheckBox explanationsField = new CheckBox("Contains answer explanations");
		private final Button cancelButton = new Button("Cancel");
		private final Button saveButton = new Button("Save");

		private PendingAnswerBookletEditor(Exam exam, Path sourcePath) {
			this.exam = exam;
			this.sourcePath = sourcePath;
			configureControls();
		}

		private void cancel() {

			// Nothing has been persisted yet. Re-read the Exam to restore exactly its
			// authoritative Answer rows and Question-booklet Answer choices.
			pendingAnswerBookletEditor = null;
			loadSelectedExamSafely(exam);
		}

		private void configureControls() {
			nameField.setId("exam-assets-new-answer-name");
			nameField.setText(defaultAnswerBookletName(sourcePath));
			nameField.setMaxWidth(Double.MAX_VALUE);
			explanationsField.setId("exam-assets-new-answer-explanations");
			cancelButton.setId("exam-assets-new-answer-cancel");
			saveButton.setId("exam-assets-new-answer-save");
			cancelButton.setOnAction(_ -> cancel());
			saveButton.setOnAction(_ -> save());

			// A blank descriptive name cannot become a persisted AnswerFile.
			nameField.textProperty().addListener((_, _, _) -> updateSaveState());
			updateSaveState();
		}

		private VBox createRow() {
			Label heading = new Label("New Answer Booklet");
			heading.setStyle(HEADING_STYLE);
			Label sourceLabel = new Label(sourceFileName(sourcePath.toString()));
			sourceLabel.setId("exam-assets-new-answer-source");
			GridPane metadata = new GridPane();
			metadata.setHgap(SPACING);
			metadata.setVgap(ROW_SPACING);
			metadata.addRow(0, new Label("Name"), nameField);
			GridPane.setHgrow(nameField, Priority.ALWAYS);
			Region actionSpacer = new Region();
			HBox.setHgrow(actionSpacer, Priority.ALWAYS);
			HBox actions = new HBox(SPACING, actionSpacer, cancelButton, saveButton);

			// The selected source remains visible while the user supplies only the
			// metadata that is not derivable from the PDF itself.
			VBox row = new VBox(ROW_SPACING, heading, sourceLabel, metadata, explanationsField, actions);
			row.setId("exam-assets-new-answer-booklet");
			row.setPadding(SECTION_PADDING);
			row.setStyle(BORDER_STYLE);
			return row;
		}

		private void save() {
			String name = nameField.getText().strip();
			if (name.isBlank()) {
				return;
			}
			try {
				answerBookletCreationHandler.create(exam, sourcePath, name, explanationsField.isSelected());
				pendingAnswerBookletEditor = null;

				// Rebuild both sections so the new Answer asset is immediately available
				// in every Question booklet's Answer dropdown.
				loadSelectedExamSafely(exam);
			} catch (IOException | SQLException | IllegalArgumentException | IllegalStateException exception) {
				String message = exception.getMessage();
				if (message == null || message.isBlank()) {
					message = exception.getClass().getSimpleName();
				}

				// Leave the pending editor intact so metadata can be corrected or the
				// operation cancelled after a failed persistence attempt.
				showAnswerFileError("Answer booklet could not be added.", message);
			}
		}

		private void updateSaveState() {

			// The source has already been selected; only a meaningful Answer asset name
			// is required before persistence can proceed.
			saveButton.setDisable(nameField.getText().isBlank());
		}
	}

	private final class QuestionBookletEditor {

		private ExamBooklet booklet;
		private final RadioButton selectionButton = new RadioButton();
		private final Label heading = new Label();
		private final ComboBox<String> nameField = new ComboBox<>();
		private final ToggleGroup formatGroup = new ToggleGroup();
		private final RadioButton mcqButton = new RadioButton("MCQ");
		private final RadioButton writtenButton = new RadioButton("Written Response");
		private final RadioButton bothButton = new RadioButton("Both");
		private final TextField expectedField = new TextField();
		private final Button viewButton = new Button("View");
		private final Button editButton = new Button("Edit");
		private final Button cancelButton = new Button("Cancel");
		private final Button saveButton = new Button("Save");
		private boolean editing;
		private final ComboBox<AnswerAssignmentChoice> answerField = new ComboBox<>();
		private AnswerFile assignedAnswerFile;

		// Restore either the exact assigned AnswerFile or the explicit No Answer
		// Booklet option.
		private QuestionBookletEditor(ExamBooklet booklet, AnswerFile assignedAnswerFile) {
			this.booklet = booklet;
			this.assignedAnswerFile = assignedAnswerFile;

			// Configure controls owned by this booklet row before persisted values are
			// applied to them.
			configureEditorControls();
			applyPersistedValues();
			setEditing(false);
		}

		private void applyPersistedValues() {
			heading.setText(booklet.getName());
			nameField.setValue(booklet.getName());
			nameField.getEditor().setText(booklet.getName());
			formatGroup.selectToggle(null);
			switch (booklet.getQuestionFormat()) {
			case MULTIPLE_CHOICE -> mcqButton.setSelected(true);
			case WRITTEN_RESPONSE -> writtenButton.setSelected(true);
			case MIXED -> bothButton.setSelected(true);
			case UNSPECIFIED -> {

				// Legacy UNSPECIFIED data remains visible as an unselected Type until the
				// user deliberately classifies the booklet.
			}
			}
			expectedField.setText(
					booklet.getExpectedQuestionCount() == null ? "" : booklet.getExpectedQuestionCount().toString());
			AnswerAssignmentChoice matchingChoice = answerField.getItems().stream()
					.filter(choice -> sameAnswerAssignment(choice.answerFile(), assignedAnswerFile)).findFirst()
					.orElse(null);
			if (matchingChoice == null) {

				// Do not assume the choice list is populated. If construction order or stale
				// persisted data means the matching entry is absent, create the exact choice
				// required to represent the authoritative assignment.
				if (assignedAnswerFile == null) {
					matchingChoice = new AnswerAssignmentChoice(null, NO_ANSWER_BOOKLET);
					answerField.getItems().add(0, matchingChoice);
				} else {
					matchingChoice = new AnswerAssignmentChoice(assignedAnswerFile, assignedAnswerFile.getName());
					answerField.getItems().add(matchingChoice);
				}
			}
			answerField.setValue(matchingChoice);
		}

		private void beginEdit() {
			if (editingBookletEditor != null || editingExamDetails || pendingQuestionBookletEditor != null
					|| pendingAnswerBookletEditor != null || booklet.getExam().isComplete()) {
				return;
			}

			// Only one structural row edit may be staged at once.
			editingBookletEditor = this;
			setEditing(true);
			editExamButton.setDisable(true);
			refreshQuestionBookletActionStates();
			updateUseSelectedBookletState();
			updateQuestionBookletAddState();
			updateAnswerBookletAddState();

			// Configuring a booklet deliberately opens its authoritative managed PDF in
			// read-only VIEWER mode so expected Question count can be checked against the
			// actual source without activating this booklet for capture.
			questionBookletViewHandler.accept(booklet);
		}

		private void cancelEdit() {

			// Cancel restores the last authoritative persisted values without writing.
			applyPersistedValues();
			editingBookletEditor = null;
			setEditing(false);
			setExamDetailsEditing(false);
			refreshQuestionBookletActionStates();
			updateUseSelectedBookletState();
			updateQuestionBookletAddState();
			updateAnswerBookletAddState();
		}

		private void configureEditorControls() {
			long bookletId = booklet.getId();
			selectionButton.setId("exam-assets-question-select-" + bookletId);
			selectionButton.setToggleGroup(questionBookletSelectionGroup);

			// Store the authoritative booklet object on the selection control so capture
			// activation does not need to reconstruct domain state from presentation text.
			selectionButton.setUserData(booklet);
			nameField.setId("exam-assets-question-name-" + bookletId);
			nameField.setEditable(true);
			nameField.setMaxWidth(Double.MAX_VALUE);
			nameField.getItems().setAll(bookletNameSuggestions);
			mcqButton.setId("exam-assets-question-format-mcq-" + bookletId);
			writtenButton.setId("exam-assets-question-format-written-" + bookletId);
			bothButton.setId("exam-assets-question-format-both-" + bookletId);
			mcqButton.setToggleGroup(formatGroup);
			writtenButton.setToggleGroup(formatGroup);
			bothButton.setToggleGroup(formatGroup);

			// Each radio button carries the exact enum value that will be persisted.
			mcqButton.setUserData(ExamBookletQuestionFormat.MULTIPLE_CHOICE);
			writtenButton.setUserData(ExamBookletQuestionFormat.WRITTEN_RESPONSE);
			bothButton.setUserData(ExamBookletQuestionFormat.MIXED);
			expectedField.setId("exam-assets-question-expected-" + bookletId);

			// Expected Question counts are at most two digits, so prevent this field from
			// expanding to the full metadata-column width.
			expectedField.setPrefColumnCount(2);
			expectedField.setMaxWidth(Region.USE_PREF_SIZE);
			answerField.setId("exam-assets-question-answer-" + bookletId);
			answerField.setMaxWidth(Double.MAX_VALUE);

			// No Answer Booklet is always available and appears first. Every AnswerFile
			// registered for the Exam follows it and may be shared by several booklets.
			List<AnswerAssignmentChoice> answerChoices = new ArrayList<>();
			answerChoices.add(new AnswerAssignmentChoice(null, NO_ANSWER_BOOKLET));

			// Answer assets are loaded once for the selected Exam by the containing
			// ExamAssetsPane and are shared by every Question-booklet editor.
			for (AnswerFile answerFile : ExamAssetsPane.this.availableAnswerFiles) {
				answerChoices.add(new AnswerAssignmentChoice(answerFile, answerFile.getName()));
			}
			answerField.getItems().setAll(answerChoices);
			viewButton.setId("exam-assets-question-view-" + bookletId);
			viewButton.setMinWidth(Region.USE_PREF_SIZE);

			// View remains inspection-only and does not activate this booklet for capture.
			viewButton.setOnAction(_ -> questionBookletViewHandler.accept(booklet));
			editButton.setId("exam-assets-question-edit-" + bookletId);
			cancelButton.setId("exam-assets-question-cancel-" + bookletId);
			saveButton.setId("exam-assets-question-save-" + bookletId);
			editButton.setOnAction(_ -> beginEdit());
			cancelButton.setOnAction(_ -> cancelEdit());
			saveButton.setOnAction(_ -> saveEdit());

			// Save becomes available only when the staged row differs from persistence and
			// all entered values remain valid.
			nameField.getEditor().textProperty().addListener((_, _, _) -> updateActionState());
			formatGroup.selectedToggleProperty().addListener((_, _, _) -> updateActionState());
			expectedField.textProperty().addListener((_, _, _) -> updateActionState());
			answerField.valueProperty().addListener((_, _, _) -> updateActionState());
			ExamBooklet activeBooklet = activeBookletSupplier.get();
			if (activeBooklet != null && activeBooklet.getId() == bookletId) {

				// Re-entering Exam/Assets preserves the current capture booklet as the
				// initially selected Question booklet.
				selectionButton.setSelected(true);
			}
		}

		private VBox createRow() {
			heading.setStyle(HEADING_STYLE);
			Label source = new Label(sourceFileName(booklet.getSourceDocument().getRelativePath()));
			Region headingSpacer = new Region();
			HBox.setHgrow(headingSpacer, Priority.ALWAYS);

			// Keep the booklet identity, source filename and inspection action together
			// on one compact line rather than spending a separate row on the filename.
			HBox headingRow = new HBox(SPACING, selectionButton, heading, source, headingSpacer, viewButton);

			// Question format is one mutually exclusive value, so all three alternatives
			// remain on one compact horizontal row.
			HBox formatControls = new HBox(SPACING, mcqButton, writtenButton, bothButton);
			formatControls.setId("exam-assets-question-format-row-" + booklet.getId());
			GridPane metadata = new GridPane();
			metadata.setHgap(SPACING);
			metadata.setVgap(ROW_SPACING);
			metadata.addRow(0, new Label("Name"), nameField);
			metadata.addRow(1, new Label("Type"), formatControls);
			metadata.addRow(2, new Label("Expected Questions"), expectedField);
			metadata.addRow(3, new Label("Answer"), answerField);
			GridPane.setHgrow(nameField, Priority.ALWAYS);
			GridPane.setHgrow(answerField, Priority.ALWAYS);
			Region actionSpacer = new Region();
			HBox.setHgrow(actionSpacer, Priority.ALWAYS);
			HBox actions = new HBox(SPACING, actionSpacer, editButton, cancelButton, saveButton);

			// The source filename no longer needs its own vertical row; the remaining
			// rows are the editable structural metadata and their transaction actions.
			VBox row = new VBox(ROW_SPACING, headingRow, metadata, actions);
			row.setId("exam-assets-question-booklet-" + booklet.getId());
			row.setPadding(SECTION_PADDING);
			row.setStyle(BORDER_STYLE);
			return row;
		}

		private Integer editedExpectedQuestionCount() {
			String text = expectedField.getText().strip();
			if (text.isBlank()) {

				// Null remains meaningful for a booklet that has not yet been reviewed.
				return null;
			}
			try {
				int count = Integer.parseInt(text);
				if (count < 1 || count > 99) {

					// Expected top-level Question counts are deliberately constrained to the
					// agreed two-digit range.
					throw new IllegalArgumentException("Expected Questions must be between 1 and 99 when supplied.");
				}
				return count;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Expected Questions must be a positive whole number.");
			}
		}

		private String editedName() {
			return nameField.getEditor().getText().strip();
		}

		private boolean hasValidEditedValues() {
			if (editedName().isBlank() || selectedFormat() == null) {
				return false;
			}
			try {
				editedExpectedQuestionCount();
				return true;
			} catch (IllegalArgumentException exception) {

				// Invalid numeric text is staged visibly but cannot be persisted.
				return false;
			}
		}

		private boolean isDirty() {
			return !editedName().equals(booklet.getName()) || selectedFormat() != booklet.getQuestionFormat()
					|| !java.util.Objects.equals(safeEditedExpectedQuestionCount(), booklet.getExpectedQuestionCount())
					|| !sameAnswerAssignment(selectedAnswerFile(), assignedAnswerFile);
		}

		private Integer safeEditedExpectedQuestionCount() {
			try {
				return editedExpectedQuestionCount();
			} catch (IllegalArgumentException exception) {

				// Invalid text is always considered different from authoritative
				// persistence, while Save remains disabled by validation.
				return Integer.MIN_VALUE;
			}
		}

		private boolean sameAnswerAssignment(AnswerFile first, AnswerFile second) {
			if (first == null || second == null) {
				return first == second;
			}

			// Persistent identity, rather than object instance, defines the assignment.
			return first.getId() == second.getId();
		}

		private void saveEdit() {
			if (!editing || saveButton.isDisabled()) {
				return;
			}
			try {
				ExamBookletQuestionFormat format = selectedFormat();
				Integer expectedQuestionCount = editedExpectedQuestionCount();

				// Metadata and Answer assignment are committed by one repository transaction
				// so Save cannot leave only part of the row updated.
				ExamBooklet updated = answerWriter.updateBookletConfiguration(booklet, editedName(), format,
						expectedQuestionCount, selectedAnswerFile());
				bookletMetadataUpdatedHandler.accept(updated);
				editingBookletEditor = null;

				// Re-read both Question and Answer relationships from persistence after the
				// successful transaction.
				loadSelectedExamSafely(updated.getExam());
			} catch (SQLException | IllegalArgumentException | IllegalStateException exception) {
				showBookletError(exception.getMessage());
			}
		}

		private AnswerFile selectedAnswerFile() {
			AnswerAssignmentChoice choice = answerField.getValue();

			// A missing or explicit No Answer Booklet choice both mean no assignment.
			return choice == null ? null : choice.answerFile();
		}

		private ExamBookletQuestionFormat selectedFormat() {
			Toggle selected = formatGroup.getSelectedToggle();
			if (selected == null) {
				return null;
			}

			// Every Type toggle stores the exact persisted enum value it represents.
			return (ExamBookletQuestionFormat) selected.getUserData();
		}

		private void setEditing(boolean editing) {
			this.editing = editing;
			nameField.setDisable(!editing);
			mcqButton.setDisable(!editing);
			writtenButton.setDisable(!editing);
			bothButton.setDisable(!editing);
			expectedField.setDisable(!editing);
			answerField.setDisable(!editing);
			updateActionState();
		}

		private void updateActionState() {

			// All Question-booklet structural operations share one transaction slot at the
			// workspace level.
			editButton.setDisable(editing || editingExamDetails || booklet.getExam().isComplete()
					|| pendingQuestionBookletEditor != null || pendingAnswerBookletEditor != null
					|| (editingBookletEditor != null && editingBookletEditor != this));
			cancelButton.setDisable(!editing);

			// Save requires a real difference and a completely valid staged row.
			saveButton.setDisable(!editing || !hasValidEditedValues() || !isDirty());
		}
	}
}
