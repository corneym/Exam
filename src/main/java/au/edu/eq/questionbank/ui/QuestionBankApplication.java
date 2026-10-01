package au.edu.eq.questionbank.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationPaths;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.curriculum.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedContextStatus;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.output.revision.RevisionAnswerAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionExportRequest;
import au.edu.eq.questionbank.output.revision.RevisionExportResult;
import au.edu.eq.questionbank.output.revision.RevisionExportService;
import au.edu.eq.questionbank.output.revision.RevisionExportValidator;
import au.edu.eq.questionbank.output.revision.RevisionQuestionAssetRenderer;
import au.edu.eq.questionbank.output.revision.RevisionSharedContextAssetRenderer;
import au.edu.eq.questionbank.output.scorm.ScormExportRequest;
import au.edu.eq.questionbank.output.scorm.ScormExportResult;
import au.edu.eq.questionbank.output.scorm.ScormExportService;
import au.edu.eq.questionbank.output.scorm.ScormManifestWriter;
import au.edu.eq.questionbank.output.scorm.ScormPackageValidator;
import au.edu.eq.questionbank.output.scorm.ScormSchemaSupport;
import au.edu.eq.questionbank.output.scorm.ScormZipWriter;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.AnswerFileReassignmentService;
import au.edu.eq.questionbank.repository.assessment.AnswerPdfReplacementService;
import au.edu.eq.questionbank.repository.assessment.ExamMetadataCorrectionService;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionMetadataService;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionMetadataUpdateResult;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService;
import au.edu.eq.questionbank.repository.assessment.QuestionBookletPdfReplacementService;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionOutputApplicabilityRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumImportConflictException;
import au.edu.eq.questionbank.repository.curriculum.CurriculumImportResult;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumAuthoringWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumImporter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumLifecycleRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumSourcePdfRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.IncompatibleDatabaseException;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.backup.AutomaticBackupRetention;
import au.edu.eq.questionbank.service.backup.BackupException;
import au.edu.eq.questionbank.service.backup.BackupKind;
import au.edu.eq.questionbank.service.backup.BackupRequest;
import au.edu.eq.questionbank.service.backup.BackupResult;
import au.edu.eq.questionbank.service.backup.DefaultBackupService;
import au.edu.eq.questionbank.service.backup.DefaultRestoreExecutor;
import au.edu.eq.questionbank.service.backup.DefaultRestoreService;
import au.edu.eq.questionbank.service.backup.RestoreException;
import au.edu.eq.questionbank.service.backup.RestorePreparation;
import au.edu.eq.questionbank.service.backup.RestoreResult;
import au.edu.eq.questionbank.service.backup.ShutdownCoordinator;
import au.edu.eq.questionbank.service.backup.ShutdownResult;
import au.edu.eq.questionbank.service.backup.ShutdownStatus;
import au.edu.eq.questionbank.service.curriculum.ConfirmedDescriptorSubtopicMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringCreationService;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringOpenService;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftLoader;
import au.edu.eq.questionbank.service.curriculum.CurriculumLifecycleService;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingCoverageService;
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfService;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfStore;
import au.edu.eq.questionbank.service.curriculum.SubtopicMappingEvidenceService;
import au.edu.eq.questionbank.service.curriculum.TfIdfCurriculumMappingSuggester;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;
import au.edu.eq.questionbank.ui.audit.QuestionCorpusAuditDialog;
import au.edu.eq.questionbank.ui.capture.AnswerCapturePane;
import au.edu.eq.questionbank.ui.capture.QuestionCapturePane;
import au.edu.eq.questionbank.ui.capture.SharedContextCapturePane;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionMetadataDialog;
import au.edu.eq.questionbank.ui.correction.LegacyQuestionSplitDialog;
import au.edu.eq.questionbank.ui.curriculum.CurriculumAuthoringPane;
import au.edu.eq.questionbank.ui.curriculum.CurriculumImportDialog;
import au.edu.eq.questionbank.ui.curriculum.CurriculumMappingReviewDialog;
import au.edu.eq.questionbank.ui.curriculum.CurriculumSelectorPane;
import au.edu.eq.questionbank.ui.curriculum.NewCurriculumDialog;
import au.edu.eq.questionbank.ui.exam.ExamAssetsPane;
import au.edu.eq.questionbank.ui.exam.ExamMetadataPane;
import au.edu.eq.questionbank.ui.exam.LegacyQuestionImportDialog;
import au.edu.eq.questionbank.ui.export.RevisionExportDialog;
import au.edu.eq.questionbank.ui.export.RevisionExportTask;
import au.edu.eq.questionbank.ui.export.ScormExportDialog;
import au.edu.eq.questionbank.ui.export.ScormExportTask;
import au.edu.eq.questionbank.ui.help.HelpDialog;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModelFactory;
import au.edu.eq.questionbank.ui.pdf.PdfFilePicker;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import au.edu.eq.questionbank.ui.search.QuestionSearchDialog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Composition root for the Exam Question Bank desktop application.
 * <p>
 * Workflow state and controls are delegated to focused panes for exam metadata,
 * PDF display, question capture, and answer capture.
 */
public class QuestionBankApplication extends Application {

	private static final int EXPORT_PROGRESS_WIDTH = 360;
	private static final int EXPORT_PROGRESS_SPACING = 10;
	private static final int AUTHORING_WINDOW_WIDTH = 1400;
	private static final int AUTHORING_WINDOW_HEIGHT = 900;
	private static final int FIRST_DUPLICATE_SUFFIX = 2;
	private static final double SECTION_SPACING = 10.0;
	private static final double PREVIEW_PANE_INITIAL_WIDTH = 525.0;
	private static final double PREVIEW_PANE_MIN_WIDTH = 400.0;
	private static final double SCENE_WIDTH = 1400.0;
	private static final double SCENE_HEIGHT = 900.0;
	private static final double INITIAL_WORKSPACE_DIVIDER_POSITION = PREVIEW_PANE_INITIAL_WIDTH / SCENE_WIDTH;
	private static final Insets PREVIEW_PANE_PADDING = new Insets(10);
	private static final Path LEGACY_PROPERTIES_FILE = Path.of("questionbank.properties").toAbsolutePath().normalize();
	private QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();
	private double captureDividerPosition = INITIAL_WORKSPACE_DIVIDER_POSITION;
	private final CaptureSelectionState captureSelectionState = new CaptureSelectionState();
	private CurriculumSelectionModel curriculumSelectionModel;
	private CurriculumSelectorPane curriculumSelectorPane;
	private ExamMetadataPane examMetadataPane;
	private QuestionCapturePane questionCapturePane;
	private AnswerCapturePane answerCapturePane;
	private ScrollPane previewScrollPane;
	private Runnable applicationExitAction = Platform::exit;
	private ShutdownCoordinator shutdownCoordinator;
	private boolean resourcesClosedForRestore;
	private MenuItem revisionExportMenuItem;
	private boolean revisionExportRunning;
	private MenuItem scormExportMenuItem;
	private boolean scormExportRunning;
	private SplitPane workspaceSplitPane;
	private SqliteExamWriter examWriter;
	private final Label activeExamBookletLabel = new Label("No Exam booklet selected");
	private final Button changeExamAssetsButton = new Button("Change Exam");

	// Curriculum persistence joins the application-owned Working Subject refresh
	// rather than being read by CurriculumSelectorPane on the JavaFX thread.
	private Function<Subject, CurriculumSelectionModel.SubjectSnapshot> workingSubjectCurriculumSnapshotLoader;

	// One loader supplies the complete Question snapshot used by both capture
	// panes.
	// Keeping it application-owned prevents duplicate repository reads per Subject
	// transition and provides a deterministic dependency for workflow tests.
	private Supplier<List<Question>> workingSubjectQuestionSnapshotLoader = List::of;

	// Every accepted Working Subject change advances this generation. Background
	// work from an earlier generation may finish, but it may never update current
	// UI.
	private long workingSubjectRefreshGeneration;

	// The application-level Subject remains outside this host so the left workspace
	// can switch between Capture and Exam/Assets without replacing Working Subject.
	private StackPane workspaceModeHost;

	// Track the accepted workspace Subject separately so a rejected ComboBox change
	// can restore the previous value without changing either capture queue.
	private Subject workingSubject;
	private boolean restoringWorkingSubject;

	// Keep both left-side modes as stable nodes. Switching modes re-parents the
	// existing pane rather than reconstructing capture controls and their state.
	private VBox captureWorkspaceModePane;
	private ExamAssetsPane examAssetsPane;

	// Exam/Assets persistence participates in the same application-level Subject
	// generation as the shared Question snapshot.
	private Function<Subject, ExamAssetsPane.SubjectSnapshot> workingSubjectExamAssetsSnapshotLoader;

	// Legacy workbook intake may pause while the user creates missing authoritative
	// Exam/booklet structure in Exam/Assets.
	private PendingLegacyQuestionImport pendingLegacyQuestionImport;

	/**
	 * Creates the desktop application instance initialized by JavaFX.
	 */
	public QuestionBankApplication() {
	}

	/**
	 * Launches the desktop application.
	 *
	 * @param args command-line arguments passed to JavaFX
	 */
	public static void main(String[] args) {
		launch(args);
	}

	@Override
	public void start(Stage stage) throws Exception {
		Path propertiesFile = ApplicationPaths.propertiesFile();
		ApplicationConfig config;
		try {

			// Installed builds store writable configuration under the user's application
			// directory. During the transition, an existing working-directory
			// questionbank.properties file is copied there once and remains untouched.
			config = ApplicationConfig.loadOrCreate(propertiesFile, LEGACY_PROPERTIES_FILE,
					ApplicationPaths.defaultDataRoot());
		} catch (ConfigurationException e) {
			showStartupError("Configuration Error", e.getMessage());
			return;
		} catch (IOException e) {
			showStartupError("Configuration Error", "Could not prepare application configuration:\n" + e.getMessage());
			return;
		}
		try {
			startApplication(stage, config);
		} catch (IncompatibleDatabaseException e) {
			showStartupError("Database Upgrade Required", """
					The existing question-bank database contains old development question data
					that cannot be migrated safely to the current database format.

					Delete the existing database and restart the application.

					Database:
					%s

					You will need to re-import the curriculum and exam data afterwards.
					""".formatted(config.databasePath()));
		} catch (SQLException e) {
			showStartupError("Database Error", """
					The question-bank database could not be opened or upgraded.

					Database:
					%s

					%s
					""".formatted(config.databasePath(), e.getMessage()));
		}
	}

	@Override
	public void stop() throws Exception {
		if (resourcesClosedForRestore) {
			return;
		}
		if (shutdownCoordinator == null || !shutdownCoordinator.isReadyToExit()) {
			pdfWorkspace.close();
		}
	}

	private boolean activateBookletForCapture(ExamBooklet booklet, ApplicationConfig config) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (!allowExamImportConfirmation()) {
			return false;
		}

		// A read-only Exam/Assets View may currently own the PDF pane. Close that
		// temporary viewer session before making a Question booklet authoritative.
		if (pdfWorkspace.getDisplayedDocument() == PdfWorkspacePane.DocumentMode.VIEWER) {
			pdfWorkspace.closeViewerPdf();
		}
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path storedPath;
		try {

			// Capture always opens the authoritative managed Question source rather than
			// an external file chosen independently of persistence.
			storedPath = pdfStore.resolve(booklet.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException exception) {
			showAlert(Alert.AlertType.ERROR, "Open Exam for Capture", "The stored Question PDF path is invalid.",
					exception.getMessage());
			return false;
		}
		if (!Files.isRegularFile(storedPath)) {
			showAlert(Alert.AlertType.ERROR, "Open Exam for Capture", "The stored Question PDF is unavailable.",
					storedPath.toString());
			return false;
		}
		try {
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, config.pdfDataRoot());

			// Reuse the established activation sequence so PDF state and Question entry
			// state are reset exactly as they are for the existing workflow.
			openExamPdf(selectedPdf);
			examMetadataPane.activateExistingBooklet(booklet, storedPath);

			// Rebuild both persisted capture queues after changing structural source
			// context so Capture immediately reflects the newly active booklet.
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			return true;
		} catch (RuntimeException exception) {
			showAlert(Alert.AlertType.ERROR, "Open Exam for Capture",
					"The selected Question booklet could not be opened.", failureMessage(exception));
			return false;
		}
	}

	private void activateExamBookletSubject(Subject subject) {

		// Activate the booklet's Subject before deriving any Question-capture
		// defaults from the newly active booklet.
		activateExamSubject(subject);
		if (questionCapturePane != null) {

			// Reopening an existing booklet must reapply its format only to the fresh
			// new-question entry state.
			questionCapturePane.refreshForActiveBooklet();
		}

		// Exam/booklet identity is application capture context and remains visible
		// independently of the current Question classification.
		refreshActiveExamContext();
	}

	private void activateExamSubject(Subject subject) {
		curriculumSelectorPane.selectSubject(subject);
	}

	private boolean activateImportedQuestion(Question question, ApplicationConfig config) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet != null && activeBooklet.getId() == question.getBooklet().getId()
				&& pdfWorkspace.hasExamPdf()) {
			if (pdfWorkspace.getDisplayedDocument() != PdfWorkspacePane.DocumentMode.EXAM) {
				pdfWorkspace.showDocument(PdfWorkspacePane.DocumentMode.EXAM);
			}
			return true;
		}
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path pdfPath;
		try {
			pdfPath = pdfStore.resolve(question.getBooklet().getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException e) {
			showAlert(Alert.AlertType.ERROR, "Question Capture", "The stored exam PDF path is invalid.",
					e.getMessage());
			return false;
		}
		if (!Files.isRegularFile(pdfPath)) {
			showAlert(Alert.AlertType.ERROR, "Question Capture", "The stored exam PDF is unavailable.",
					pdfPath.toString());
			return false;
		}
		try {
			pdfWorkspace.openExamPdf(pdfPath);
			examMetadataPane.activateExistingBooklet(question.getBooklet(), pdfPath);
			return true;
		} catch (RuntimeException e) {
			showAlert(Alert.AlertType.ERROR, "Question Capture", "The stored exam PDF could not be opened.",
					e.getMessage());
			return false;
		}
	}

	private void addSubject(Stage primaryStage, SqliteDatabase database, CurriculumSelectorPane selectorPane) {

		// Creating and immediately activating a Subject is a Working Subject change.
		// Preserve the existing safeguard against abandoning unsaved capture work.
		if (!allowWorkingSubjectChange()) {
			return;
		}
		TextInputDialog dialog = new TextInputDialog();
		dialog.initOwner(primaryStage);
		dialog.setTitle("Add Subject");
		dialog.setHeaderText("Create a new Subject");
		dialog.setContentText("Subject name:");
		dialog.getEditor().setId("new-subject-name");
		Optional<String> result = dialog.showAndWait();
		if (result.isEmpty()) {
			return;
		}
		String subjectName = result.get().strip();
		if (subjectName.isBlank()) {
			showAlert(Alert.AlertType.WARNING, "Add Subject", "Subject name is required.",
					"Enter a name for the new Subject.");
			return;
		}

		// Treat names case-insensitively at the UI boundary so entries such as
		// Chemistry and chemistry cannot become separate application Subjects.
		boolean subjectAlreadyExists = curriculumSelectionModel.getSubjects().stream()
				.anyMatch(subject -> subject.getName().equalsIgnoreCase(subjectName));
		if (subjectAlreadyExists) {
			showAlert(Alert.AlertType.WARNING, "Add Subject", "That Subject already exists.",
					"Choose the existing Subject from the Subject list.");
			return;
		}
		try {
			Subject createdSubject = new SqliteCurriculumWriter(database).insertSubject(subjectName);

			// Reload from the authoritative repository before selecting the newly
			// persisted Subject so the ComboBox contains its real persistent identity.
			selectorPane.refreshSubjects();
			selectorPane.selectSubject(createdSubject);
		} catch (SQLException exception) {
			showAlert(Alert.AlertType.ERROR, "Add Subject", "The Subject could not be created.",
					exception.getMessage());
		}
	}

	private boolean allowAnswerCaptureTransition() {
		if (captureSelectionState.isOwnedBy(CaptureSelectionOwner.ANSWER)) {
			showAlert(Alert.AlertType.WARNING, "Answer capture", "Answer selection pending",
					"Add or clear the current answer selection before changing the answer target or PDF.");
			return false;
		}
		if (answerCapturePane == null || !answerCapturePane.hasAcceptedRegions()) {
			return true;
		}
		ButtonType discardButton = new ButtonType("Discard regions", ButtonBar.ButtonData.OK_DONE);
		ButtonType keepButton = new ButtonType("Keep current answer", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle("Unsaved answer regions");
		alert.setHeaderText("Discard accepted answer regions?");
		alert.setContentText("The current answer has accepted regions that have not been saved.");
		alert.getButtonTypes().setAll(discardButton, keepButton);
		Optional<ButtonType> result = alert.showAndWait();
		return result.isPresent() && result.get() == discardButton;
	}

	private boolean allowAnswerPdfReplacement() {
		boolean captureWorkInProgress = captureSelectionState.hasPendingSelection()
				|| questionCapturePane.hasAcceptedRegions() || answerCapturePane.hasAcceptedRegions()
				|| questionCapturePane.isCapturingSharedContext() || questionCapturePane.isSaveInProgress()
				|| answerCapturePane.isSaveInProgress() || answerCapturePane.isEditingAnswer();
		if (!captureWorkInProgress) {
			return true;
		}

		// AnswerFile correction can invalidate persisted Answer regions and rebuild the
		// unanswered queue. No transient Answer edit or capture may survive that
		// change.
		showAlert(Alert.AlertType.WARNING, "Replace Answer PDF", "Capture work is in progress",
				"Save, clear or cancel the current Question, Shared Context or Answer work before replacing the Answer PDF.");
		return false;
	}

	private boolean allowExamAssetsTransition() {
		boolean captureWorkInProgress = captureSelectionState.hasPendingSelection()
				|| questionCapturePane.hasAcceptedRegions() || answerCapturePane.hasAcceptedRegions()
				|| questionCapturePane.isCapturingSharedContext() || questionCapturePane.isSaveInProgress()
				|| answerCapturePane.isSaveInProgress() || answerCapturePane.isEditingAnswer();
		if (!captureWorkInProgress) {
			return true;
		}

		// Exam/Assets can later change the active source structure, so never leave
		// Capture mode while unsaved work still depends on its current Exam or PDF.
		showAlert(Alert.AlertType.WARNING, "Exam / Assets", "Capture work is in progress",
				"Save, clear or cancel the current Question, Shared Context or Answer work before managing Exam assets.");
		return false;
	}

	private boolean allowExamImportConfirmation() {
		if (captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION)) {

			// Do not allow the active exam to change while a Question selection is pending.
			showAlert(Alert.AlertType.WARNING, "Open Exam for Capture", "Question selection pending",
					"Add or clear the current question selection before opening another exam for capture.");
			return false;
		}
		return confirmDiscardAcceptedQuestionRegions();
	}

	private boolean allowExamLifecycleChange() {
		boolean captureWorkInProgress = captureSelectionState.hasPendingSelection()
				|| questionCapturePane.hasAcceptedRegions() || answerCapturePane.hasAcceptedRegions()
				|| questionCapturePane.isCapturingSharedContext() || questionCapturePane.isSaveInProgress()
				|| answerCapturePane.isSaveInProgress() || answerCapturePane.isEditingAnswer();
		if (!captureWorkInProgress) {
			return true;
		}

		// Completing or reactivating changes which structural operations persistence
		// will accept, so do not change lifecycle underneath unfinished capture work.
		showAlert(Alert.AlertType.WARNING, "Exam State", "Capture work is in progress",
				"Save, clear or cancel the current Question, Shared Context or Answer work before changing the Exam state.");
		return false;
	}

	private boolean allowPdfPageNavigation() {
		if (!captureSelectionState.hasPendingSelection()) {
			return true;
		}
		showAlert(Alert.AlertType.WARNING, "Selection pending", "Selection pending",
				"Add or clear the current selection before changing PDF pages.");
		return false;
	}

	private boolean allowQuestionPdfReplacement() {
		boolean captureWorkInProgress = captureSelectionState.hasPendingSelection()
				|| questionCapturePane.hasAcceptedRegions() || answerCapturePane.hasAcceptedRegions()
				|| questionCapturePane.isCapturingSharedContext() || questionCapturePane.isSaveInProgress()
				|| answerCapturePane.isSaveInProgress();
		if (!captureWorkInProgress) {
			return true;
		}

		// Replacing the Question PDF invalidates persisted source coordinates and may
		// rebuild capture queues, so no unsaved capture state may remain active.
		showAlert(Alert.AlertType.WARNING, "Replace Question PDF", "Capture work is in progress",
				"Save, add, clear or cancel the current Question, Shared Context or Answer capture before replacing the Question PDF.");
		return false;
	}

	private boolean allowWorkingSubjectChange() {
		boolean captureWorkInProgress = captureSelectionState.hasPendingSelection()
				|| questionCapturePane.hasAcceptedRegions() || answerCapturePane.hasAcceptedRegions()
				|| questionCapturePane.isCapturingSharedContext() || questionCapturePane.isSaveInProgress()
				|| answerCapturePane.isSaveInProgress() || answerCapturePane.isEditingAnswer();
		if (!captureWorkInProgress) {
			return true;
		}

		// Subject changes rebuild capture queues and can invalidate the active Exam.
		// Never permit that transition while unsaved capture or Answer-edit state still
		// depends on the current Subject or PDF.
		showAlert(Alert.AlertType.WARNING, "Working Subject", "Capture work is in progress",
				"Add, clear, save or cancel the current Question, shared-context or Answer capture before changing Working Subject.");
		return false;
	}

	private String applicationVersion() {

		// Read the Maven-filtered application metadata so About, backups, version
		// information and later packaging all use the same authoritative version.
		return au.edu.eq.questionbank.ApplicationVersion.current();
	}

	private void backupNow(Stage primaryStage, ApplicationConfig config) {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Choose Backup Destination");
		File selectedDirectory = chooser.showDialog(primaryStage);
		if (selectedDirectory == null) {
			return;
		}
		try {
			DefaultBackupService backupService = new DefaultBackupService(config, applicationVersion());
			BackupResult result = backupService.createBackup(BackupRequest.full(selectedDirectory.toPath()));
			showAlert(Alert.AlertType.INFORMATION, "Backup", "Backup completed successfully.",
					"The full question-bank backup was saved to:\n\n" + result.backupPath());
		} catch (BackupException e) {
			showAlert(Alert.AlertType.ERROR, "Backup", "The backup could not be completed.", failureMessage(e));
		}
	}

	private boolean blockWhileCaptureSaveInProgress(Stage primaryStage, String actionDescription) {
		boolean questionSaveInProgress = questionCapturePane != null && questionCapturePane.isSaveInProgress();
		boolean answerSaveInProgress = answerCapturePane != null && answerCapturePane.isSaveInProgress();
		if (!questionSaveInProgress && !answerSaveInProgress) {
			return false;
		}
		showAlert(Alert.AlertType.WARNING, "Save in progress", "Save in progress",
				"Wait for the current Question or Answer save to finish before " + actionDescription + ".");
		return true;
	}

	private void cancelPendingLegacyQuestionImport() {
		pendingLegacyQuestionImport = null;

		// Presentation follows the application-owned pending state rather than
		// retaining an independently meaningful legacy transaction.
		if (examAssetsPane != null) {
			examAssetsPane.clearLegacyImportRequirements();
		}
	}

	private Path chooseAnswerBookletSource(Stage primaryStage, ApplicationConfig config) {
		PdfFilePicker picker = new PdfFilePicker(config.pdfDataRoot());

		// Source selection is application-owned because it interacts with the native
		// filesystem rather than Exam/Assets presentation state.
		return picker.chooseAnyPdf(primaryStage, "Choose Answer booklet PDF");
	}

	private CurriculumAuthoringSession chooseExistingCurriculum(Stage primaryStage, List<SyllabusVersion> versions,
			CurriculumAuthoringOpenService openService) {
		if (versions.isEmpty()) {
			return null;
		}
		ChoiceDialog<SyllabusVersion> dialog = new ChoiceDialog<>(versions.get(0), versions);
		dialog.initOwner(primaryStage);
		dialog.setTitle("Curriculum Authoring");
		dialog.setHeaderText("Choose an existing syllabus to edit");
		dialog.setContentText("Syllabus:");
		Optional<SyllabusVersion> selection = dialog.showAndWait();
		if (selection.isEmpty()) {
			return null;
		}
		try {
			return openService.open(selection.get());
		} catch (RuntimeException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Authoring", "The curriculum could not be opened.",
					e.getMessage());
			return null;
		}
	}

	private Path chooseQuestionBookletSource(Stage primaryStage, ApplicationConfig config) {
		PdfFilePicker picker = new PdfFilePicker(config.pdfDataRoot());

		// Native filesystem interaction remains outside ExamAssetsPane so the pane can
		// be workflow-tested without automating a platform FileChooser.
		return picker.chooseAnyPdf(primaryStage, "Choose Question booklet PDF");
	}

	/**
	 * Transfers ownership of a newly completed PDF selection and removes stale
	 * local pending-selection state from the previous workflow.
	 *
	 * @param newOwner workflow receiving the newly completed selection
	 */
	private void claimCaptureSelection(CaptureSelectionOwner newOwner) {
		CaptureSelectionOwner previousOwner = captureSelectionState.getOwner();

		// Record the new owner first. Any local cleanup performed below must not clear
		// the newly completed rectangle from the shared PDF workspace.
		captureSelectionState.claim(newOwner);
		if (previousOwner == null || previousOwner == newOwner) {
			return;
		}
		if (previousOwner == CaptureSelectionOwner.ANSWER) {

			// A new Question-side selection supersedes the stale Answer selection.
			answerCapturePane.discardCurrentSelectionForOwnershipLoss();
			return;
		}

		// QUESTION and SHARED_CONTEXT both live inside QuestionCapturePane, which
		// clears the appropriate stale local state without touching the PDF rectangle.
		questionCapturePane.discardCurrentSelectionForOwnershipLoss();
	}

	private void clearCaptureSelection(CaptureSelectionOwner owner) {
		if (captureSelectionState.clear(owner)) {
			pdfWorkspace.clearSelection();
		}
	}

	private void clearPdfWorkspaceForSubjectChange(Subject newSubject) {
		ExamBooklet activeBooklet = examMetadataPane == null ? null : examMetadataPane.getBooklet();
		boolean newSubjectExamAlreadyDisplayed = newSubject != null && activeBooklet != null
				&& activeBooklet.getExam().getSubject().equals(newSubject) && pdfWorkspace.hasExamPdf()
				&& pdfWorkspace.getDisplayedDocument() == PdfWorkspacePane.DocumentMode.EXAM;
		if (newSubjectExamAlreadyDisplayed) {

			// Booklet activation opens the new Subject's Exam PDF before its Subject
			// callback runs. Do not erase that newly established context.
			return;
		}
		try {

			// A normal Working Subject change invalidates Exam, Answer and viewer
			// documents belonging to the previous application context immediately.
			pdfWorkspace.clearDocuments();
		} catch (RuntimeException exception) {

			// clearDocuments removes visible stale state even when resource closing
			// reports a failure. Surface the resource problem without undoing the
			// already accepted Subject transition.
			showAlert(Alert.AlertType.ERROR, "Working Subject",
					"The previous PDF workspace could not be closed cleanly.", failureMessage(exception));
		}
	}

	private void closePendingQuestionBookletSourceFromExamAssets() {

		// Closing VIEWER restores the Exam or Answer document that was displayed before
		// the temporary new-booklet inspection began.
		pdfWorkspace.closeViewerPdf();
	}

	private void closeRestorePreparation(Stage primaryStage, RestorePreparation preparation) {
		try {
			preparation.close();
		} catch (IOException e) {
			showAlert(Alert.AlertType.WARNING, "Restore Backup",
					"Temporary restore files could not be completely removed.", e.getMessage());
		}
	}

	private void closeViewerPdf() {

		// Viewer closing is now a generic PDF operation. Exam-booklet planning is owned
		// directly by Exam/Assets rather than being triggered indirectly when an old
		// modal inspection session closes.
		pdfWorkspace.closeViewerPdf();
		setViewerMode(false);
	}

	private void completeExitWithoutBackup(Stage primaryStage) {
		ShutdownResult result = shutdownCoordinator.exitWithoutBackup();
		if (result.exitAllowed()) {
			applicationExitAction.run();
			return;
		}
		showResourceCloseFailure(primaryStage, result.failure());
	}

	private void completeRevisionExport(Task<RevisionExportResult> task, Alert progressAlert) {
		finishRevisionExport();
		progressAlert.close();
		showRevisionExportSuccess(task.getValue());
	}

	private void completeScormExport(Task<ScormExportResult> task, Alert progressAlert) {
		finishScormExport();
		progressAlert.close();
		showScormExportSuccess(task.getValue());
	}

	private void completeWorkingSubjectCaptureRefresh(Subject subject, long generation,
			WorkingSubjectCaptureSnapshot snapshot) {
		if (!isCurrentWorkingSubjectRefresh(subject, generation)) {

			// A later Subject selection is already authoritative. Neither curriculum nor
			// queue data from this completed worker may be published.
			return;
		}
		if (curriculumSelectorPane != null) {

			// Curriculum is published first so any Question subsequently exposed by the
			// queues can immediately restore its classification path.
			curriculumSelectorPane.applySubjectSnapshot(snapshot.curriculumSnapshot());
		}
		if (questionCapturePane != null) {
			questionCapturePane.setWorkingSubject(subject, snapshot.questions());
		}
		if (answerCapturePane != null) {
			answerCapturePane.setWorkingSubject(subject, snapshot.questions());
		}
	}

	private void configurePdfWorkspace() {
		pdfWorkspace.setSelectionAvailable(this::isRegionSelectionAvailable);
		pdfWorkspace.setSelectionHandler(this::handleRegionSelection);
		pdfWorkspace.setPageNavigationAllowed(this::allowPdfPageNavigation);

		// A selection becomes invalid either because the selection mode changed or
		// because the user clicked away from an existing pending rectangle.
		pdfWorkspace.setSelectionModeChangedHandler(this::handlePdfSelectionInvalidated);
		pdfWorkspace.setSelectionCancelledHandler(this::handlePdfSelectionInvalidated);
	}

	private void configurePrimaryStage(Stage primaryStage, ApplicationConfig config) {
		primaryStage.setOnCloseRequest(event -> handleCloseRequest(event, primaryStage));

		// The system clipboard has no JavaFX change notification. Returning focus to
		// the application is the reliable point at which a Snipping Tool capture can
		// make Add From Clipboard newly available.
		primaryStage.focusedProperty().addListener((_, _, focused) -> {
			if (focused && questionCapturePane != null) {
				questionCapturePane.refreshClipboardImageAvailability();
			}
		});

		// Exam creation and asset management now live entirely in the main-window
		// Exam/Assets workspace. No modal Exam Setup or Add Exam dialog is constructed.
		showStage(primaryStage, createRootLayout(primaryStage, config));
	}

	private void configureShutdown(ApplicationConfig config) {
		DefaultBackupService automaticBackupService = new DefaultBackupService(config, applicationVersion());
		shutdownCoordinator = new ShutdownCoordinator(automaticBackupService, BackupRequest.automaticDatabase(config),
				new AutomaticBackupRetention(), pdfWorkspace);
	}

	private boolean confirmAnswerPdfReplacement(Stage primaryStage, ExamBooklet booklet,
			AnswerFileReassignmentService.Impact impact) {
		ButtonType replaceButton = new ButtonType("Replace Answer PDF", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(primaryStage);
		alert.setTitle("Replace Answer PDF");
		alert.getButtonTypes().setAll(replaceButton, cancelButton);
		if (impact.answerRegionCount() > 0) {
			alert.setHeaderText("Existing Answer regions will be invalidated.");
			alert.setContentText("""
					Booklet: %s

					Questions affected: %d
					Answer regions removed: %d
					Answers retaining independent text: %d
					Region-only Answers returned to capture: %d

					Independent Answer text, including stored MCQ A/B/C/D letters, will be retained.

					Continue only if the currently assigned Answer PDF is incorrect.
					""".formatted(booklet.getName(), impact.affectedQuestionCount(), impact.answerRegionCount(),
					impact.preservedTextAnswerCount(), impact.regionOnlyAnswerCount()));
		} else {
			alert.setHeaderText("Replace the assigned Answer PDF?");
			alert.setContentText("""
					Booklet: %s

					No persisted Answer regions currently depend on the assigned PDF.
					""".formatted(booklet.getName()));
		}

		// Closing or dismissing the confirmation never performs structural correction.
		return alert.showAndWait().orElse(cancelButton) == replaceButton;
	}

	private boolean confirmCurriculumAuthoringClose(Stage authoringStage, CurriculumAuthoringPane authoringPane) {
		if (!authoringPane.hasUnsavedChanges()) {
			return true;
		}
		ButtonType saveButton = new ButtonType("Save and close", ButtonBar.ButtonData.OK_DONE);
		ButtonType discardButton = new ButtonType("Discard changes", ButtonBar.ButtonData.NO);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(authoringStage);
		alert.setTitle("Unsaved curriculum changes");
		alert.setHeaderText("Save changes before closing?");
		alert.setContentText("The curriculum contains changes that have not been saved.");
		alert.getButtonTypes().setAll(saveButton, discardButton, cancelButton);
		ButtonType result = alert.showAndWait().orElse(cancelButton);
		if (result == cancelButton) {
			return false;
		}
		if (result == discardButton) {
			return true;
		}
		try {
			authoringPane.saveCurriculum();
			return true;
		} catch (RuntimeException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Authoring", "The curriculum could not be saved.",
					e.getMessage());
			return false;
		}
	}

	private boolean confirmDiscardAcceptedQuestionRegions() {
		if (questionCapturePane == null || !questionCapturePane.hasAcceptedRegions()) {
			return true;
		}
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.setTitle("Unsaved question regions");
		alert.setHeaderText("Discard accepted question regions?");
		alert.setContentText("The current question has accepted regions that have not been saved.");
		Optional<ButtonType> result = alert.showAndWait();
		return result.isPresent() && result.get() == ButtonType.OK;
	}

	private boolean confirmQuestionPdfReplacement(Stage primaryStage, ExamBooklet booklet,
			QuestionBookletPdfReplacementService.Impact impact) {
		ButtonType replaceButton = new ButtonType("Replace PDF", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(primaryStage);
		alert.setTitle("Replace Question PDF");
		alert.getButtonTypes().setAll(replaceButton, cancelButton);
		if (impact.hasSourceDependentCapture()) {
			alert.setHeaderText("Existing PDF-derived capture will be invalidated.");
			alert.setContentText(
					"""
							Booklet: %s

							Questions affected: %d
							Question PDF regions removed: %d
							Shared Contexts removed: %d
							Stored image parts preserved: %d

							Question identity, marks, classification, response type, Answer data and independent stored images will be retained.

							Continue only if the currently managed Question PDF is incorrect.
							"""
							.formatted(booklet.getName(), impact.affectedQuestionCount(), impact.pdfRegionCount(),
									impact.sharedContextCount(), impact.preservedImageCount()));
		} else {
			alert.setHeaderText("Replace the managed Question PDF?");
			alert.setContentText("""
					Booklet: %s

					No persisted Question regions or Shared Contexts currently depend on this PDF.
					Question metadata and other Exam data will be retained.
					""".formatted(booklet.getName()));
		}

		// Replacement never occurs merely because the dialog was closed or dismissed.
		return alert.showAndWait().orElse(cancelButton) == replaceButton;
	}

	private boolean confirmRestore(Stage primaryStage, RestorePreparation preparation) {
		String restoredData;
		if (preparation.manifest().kind() == BackupKind.FULL) {
			restoredData = """
					This will replace:
					• the question-bank database
					• managed PDF files
					• managed curriculum files
					""";
		} else {
			restoredData = """
					This will replace the question-bank database only.

					Managed PDF and curriculum files will not be changed.
					""";
		}
		ButtonType restoreButton = new ButtonType("Restore Backup", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(primaryStage);
		alert.setTitle("Restore Backup");
		alert.setHeaderText("Restore this validated backup?");
		alert.setContentText("""
				Backup type: %s
				Created: %s
				Database schema: %d

				%s

				A full safety backup of the current data will be created before anything is replaced.

				After a successful restore, the application will close and must be restarted.
				""".formatted(preparation.manifest().kind(), preparation.manifest().createdAt(),
				preparation.manifest().databaseSchemaVersion(), restoredData));
		alert.getButtonTypes().setAll(restoreButton, cancelButton);
		Optional<ButtonType> result = alert.showAndWait();
		return result.isPresent() && result.get() == restoreButton;
	}

	private void continueLegacyQuestionImport(Stage primaryStage, ApplicationConfig config,
			PendingLegacyQuestionImport pending) {
		if (!Objects.equals(workingSubject, pending.subject())) {

			// A later Working Subject has become authoritative. Silently abandon the
			// obsolete callback rather than importing into stale application context.
			cancelPendingLegacyQuestionImport();
			return;
		}
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			LegacyQuestionMetadataImporter importer = new LegacyQuestionMetadataImporter(database);
			List<LegacyBookletRequirement> missing = importer.findMissingBooklets(pending.workbookPath(),
					pending.subject().getName(), pending.syllabusVersion().getName());
			if (!missing.isEmpty()) {
				pendingLegacyQuestionImport = pending;

				// Historical workbook evidence may identify provider/year/booklet but
				// cannot manufacture authoritative Exam planning or source assets.
				examAssetsPane.showLegacyImportRequirements(pending.syllabusVersion().getName(), pending.workbookPath(),
						missing, () -> recheckPendingLegacyQuestionImport(primaryStage, config),
						this::cancelPendingLegacyQuestionImport);
				return;
			}

			// Only a completely resolved and unambiguous authoritative booklet hierarchy
			// may receive the legacy Question rows.
			LegacyQuestionImportResult importResult = importer.importWorkbook(pending.workbookPath(),
					pending.subject().getName(), pending.syllabusVersion().getName());

			// The structural preflight is now complete. Remove its workspace state before
			// refreshing the capture queues from the committed corpus.
			cancelPendingLegacyQuestionImport();

			// Corpus loading is deliberately separated from the atomic import so the
			// potentially large post-import repository read cannot block JavaFX.
			startLegacyQuestionCaptureRefresh(pending.subject(), importResult);
		} catch (IOException exception) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "Could not read the Excel workbook.",
					exception.getMessage());
		} catch (SQLException exception) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "Could not save the Question metadata.",
					exception.getMessage());
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "The legacy Question import failed.",
					exception.getMessage());
		}
	}

	private Exam correctExamMetadataAndReloadCapture(Exam exam, String providerName, int year, String assessmentName)
			throws SQLException, IOException {
		PdfWorkspacePane.DocumentMode displayedBeforeCorrection = pdfWorkspace.getDisplayedDocument();
		int pageBeforeCorrection = pdfWorkspace.getCurrentPageNumber();
		try {

			// PDFBox sessions must release their file handles before managed files are
			// renamed, particularly on Windows.
			pdfWorkspace.closeManagedPdfSessions();
			Exam corrected = examMetadataPane.correctExamMetadata(exam, providerName, year, assessmentName);

			// Replace Question instances that still contain the old Exam metadata before
			// asking either capture pane to reopen a managed document.
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			restoreManagedPdfSessions(displayedBeforeCorrection, pageBeforeCorrection);

			// Provider, year or assessment name may have changed while the booklet
			// identity stayed active.
			refreshActiveExamContext();
			return corrected;
		} catch (SQLException | IOException | RuntimeException failure) {

			// Filesystem rollback restores the old paths when correction fails. Reopen
			// whichever capture documents were active before the attempt.
			try {
				restoreManagedPdfSessions(displayedBeforeCorrection, pageBeforeCorrection);
			} catch (RuntimeException restoreFailure) {
				failure.addSuppressed(restoreFailure);
			}
			throw failure;
		}
	}

	private VBox createActiveExamContextPane() {
		Label heading = new Label("Active Exam / Booklet");
		heading.setStyle("-fx-font-weight: bold;");
		activeExamBookletLabel.setId("active-exam-booklet");
		activeExamBookletLabel.setWrapText(true);
		activeExamBookletLabel.setMaxWidth(Double.MAX_VALUE);
		changeExamAssetsButton.setId("change-exam-assets");
		changeExamAssetsButton.setText("Change Exam");
		changeExamAssetsButton.setDisable(workingSubject == null);

		// The action must remain fully readable rather than being compressed when the
		// left workspace is narrow.
		changeExamAssetsButton.setMinWidth(Region.USE_PREF_SIZE);

		// Main-window capture now transitions to the new Exam/Assets workspace rather
		// than opening the transitional modal Exam Setup surface.
		changeExamAssetsButton.setOnAction(_ -> showExamAssetsMode());
		Region spacer = new javafx.scene.layout.Region();

		// Heading and action share the first row. The spacer pushes Change Exam to the
		// right without competing with the longer Exam/booklet description below.
		HBox headerRow = new javafx.scene.layout.HBox(6.0, heading, spacer, changeExamAssetsButton);
		HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

		// The Exam/booklet identity occupies the complete second row so it can wrap
		// naturally without being squeezed by the action button.
		VBox context = new VBox(4.0, headerRow, activeExamBookletLabel);
		context.setId("active-exam-context");
		context.setPadding(new Insets(8));
		context.setStyle("-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;");
		return context;
	}

	private VBox createCaptureWorkspaceModePane() {

		// Capture mode owns both the active Exam/booklet context and the existing
		// Classification/Question/Answer workspace. A later Exam/Assets mode can
		// replace this whole unit without disturbing the application Working Subject.
		VBox captureModePane = new VBox(SECTION_SPACING, createActiveExamContextPane(), createCaptureWorkspacePane());
		captureModePane.setId("capture-workspace-mode");
		return captureModePane;
	}

	private VBox createCaptureWorkspacePane() {
		VBox classificationContext = curriculumSelectorPane.detachClassificationContext();
		VBox captureWorkspace = new VBox(SECTION_SPACING, classificationContext, questionCapturePane,
				answerCapturePane);
		captureWorkspace.setId("capture-workspace");
		captureWorkspace.setPadding(new Insets(8));

		// The outer container groups Classification, Question and Answer without
		// visually overpowering their individual section boundaries.
		captureWorkspace.setStyle("-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;");
		return captureWorkspace;
	}

	private Menu createCurriculumMenu(Stage primaryStage, ApplicationConfig config) {
		Menu curriculumMenu = createMenu("_Curriculum");
		MenuItem authorItem = createMenuItem("_Author / Edit...", () -> showCurriculumAuthoring(primaryStage, config));

		// Retain the existing id so any UI automation referring to this menu action
		// remains compatible.
		authorItem.setId("author-curriculum-pdf");
		curriculumMenu.getItems().addAll(createMenuItem("_Import...", () -> importCurriculum(primaryStage, config)),
				authorItem, new SeparatorMenuItem(),
				createMenuItem("_Review Mappings...", () -> reviewCurriculumMappings(primaryStage, config)));
		return curriculumMenu;
	}

	private CurriculumSelectorPane createCurriculumSelectorPane(Stage primaryStage, SqliteDatabase database) {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);

		// Working Subject changes are coordinated centrally so the pane never performs
		// its Subject-dependent persistence reads on the JavaFX thread.
		selectorPane.setSubjectRefreshManagedExternally(true);
		selectorPane.selectedSubjectProperty().addListener((_, _, newSubject) -> handleSubjectChanged(newSubject));

		// Subject creation belongs to application context because it persists directly
		// to the question-bank database and then changes the authoritative Working
		// Subject.
		selectorPane.setAddSubjectAction(() -> addSubject(primaryStage, database, selectorPane));
		return selectorPane;
	}

	private Menu createExamMenu(Stage primaryStage, ApplicationConfig config) {
		Menu examMenu = createMenu("_Exam");
		MenuItem examAssetsItem = createMenuItem("_Exam / Assets...", this::showExamAssetsMode);

		// Exam/Assets is the single Exam-management entry point. Legacy Question
		// intake now begins inside that workspace rather than from a parallel menu
		// item.
		examAssetsItem.setId("open-exam-for-capture");
		MenuItem markCompleteItem = createMenuItem("_Mark Active Exam Complete...",
				() -> markActiveExamComplete(primaryStage));
		markCompleteItem.setId("mark-active-exam-complete");
		MenuItem reactivateItem = createMenuItem("_Reactivate Active Exam", this::reactivateActiveExam);
		reactivateItem.setId("reactivate-active-exam");
		MenuItem replaceQuestionPdfItem = createMenuItem("_Replace Active Question PDF...",
				() -> replaceActiveQuestionPdf(primaryStage, config));
		replaceQuestionPdfItem.setId("replace-active-question-pdf");

		// Answer correction remains booklet-scoped. A genuinely shared AnswerFile is
		// preserved while only the active booklet assignment is corrected.
		MenuItem replaceAnswerPdfItem = createMenuItem("Replace Active _Answer PDF...",
				() -> replaceActiveAnswerPdf(primaryStage, config));
		replaceAnswerPdfItem.setId("replace-active-answer-pdf");
		examMenu.getItems().addAll(examAssetsItem, new SeparatorMenuItem(), markCompleteItem, reactivateItem,
				new SeparatorMenuItem(), replaceQuestionPdfItem, replaceAnswerPdfItem);
		return examMenu;
	}

	private Menu createExportMenu(Stage primaryStage, ApplicationConfig config) {
		Menu exportMenu = createMenu("E_xport");
		revisionExportMenuItem = createMenuItem("_Revision HTML...",
				() -> showRevisionExportDialog(primaryStage, config));
		revisionExportMenuItem.setId("export-revision-html");
		scormExportMenuItem = createMenuItem("Revision _SCORM...", () -> showScormExportDialog(primaryStage, config));
		scormExportMenuItem.setId("export-revision-scorm");
		exportMenu.getItems().addAll(revisionExportMenuItem, scormExportMenuItem);
		return exportMenu;
	}

	private Alert createExportProgressAlert(Stage primaryStage, Task<?> task, String title, String header,
			String initialMessage) {
		Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
		progressAlert.initOwner(primaryStage);
		progressAlert.setTitle(title);
		progressAlert.setHeaderText(header);
		Label progressLabel = new Label(initialMessage);
		progressLabel.setWrapText(true);
		progressLabel.textProperty().bind(task.messageProperty());
		ProgressBar progressBar = new ProgressBar();
		progressBar.setPrefWidth(EXPORT_PROGRESS_WIDTH);
		progressBar.progressProperty().bind(task.progressProperty());
		VBox progressContent = new VBox(EXPORT_PROGRESS_SPACING, progressLabel, progressBar);
		progressAlert.getDialogPane().setContent(progressContent);
		progressAlert.getDialogPane().setGraphic(null);
		ButtonType hideButton = new ButtonType("Hide", ButtonBar.ButtonData.CANCEL_CLOSE);
		progressAlert.getButtonTypes().setAll(hideButton);
		return progressAlert;
	}

	private Menu createFileMenu(Stage primaryStage, ApplicationConfig config) {
		Menu fileMenu = createMenu("_File");
		Menu openMenu = createMenu("_Open");
		openMenu.getItems().add(createMenuItem("_PDF...", () -> openViewerPdf(primaryStage, config)));
		fileMenu.getItems().addAll(openMenu, createMenuItem("_Close PDF", this::closeViewerPdf),
				new SeparatorMenuItem(), createMenuItem("_Backup Now...", () -> backupNow(primaryStage, config)),
				createMenuItem("_Restore Backup...", () -> restoreBackup(primaryStage, config)),
				new SeparatorMenuItem(), createMenuItem("Op_tions...", () -> showOptions(primaryStage, config)),
				new SeparatorMenuItem(), createMenuItem("E_xit", () -> requestApplicationExit(primaryStage)));
		return fileMenu;
	}

	private Menu createHelpMenu(ApplicationConfig config) {
		Menu helpMenu = createMenu("_Help");
		helpMenu.getItems().addAll(createMenuItem("_Help Contents...", this::showHelpContents), new SeparatorMenuItem(),
				createMenuItem("_About...", this::showAbout),
				createMenuItem("_Version Information...", () -> showVersionInformation(config)));
		return helpMenu;
	}

	private Menu createMenu(String text) {
		Menu menu = new Menu(text);
		menu.setMnemonicParsing(true);
		return menu;
	}

	private MenuBar createMenuBar(Stage primaryStage, ApplicationConfig config) {
		MenuBar menuBar = new MenuBar();
		menuBar.getMenus().addAll(createFileMenu(primaryStage, config), createExamMenu(primaryStage, config),
				createCurriculumMenu(primaryStage, config), createQuestionMenu(primaryStage, config),
				createExportMenu(primaryStage, config), createHelpMenu(config));
		return menuBar;
	}

	private MenuItem createMenuItem(String text, Runnable action) {
		MenuItem item = new MenuItem(text);
		item.setOnAction(_ -> action.run());
		item.setMnemonicParsing(true);
		return item;
	}

	private CurriculumAuthoringSession createNewCurriculum(Stage primaryStage,
			SqliteCurriculumRepository curriculumRepository, CurriculumAuthoringCreationService creationService) {
		NewCurriculumDialog dialog = new NewCurriculumDialog(primaryStage, curriculumRepository.findAllSubjects());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return null;
		}
		try {
			return creationService.create(dialog.getSubjectName(), dialog.getVersionName(), dialog.isCurrent());
		} catch (IllegalArgumentException e) {
			showAlert(Alert.AlertType.ERROR, "New Curriculum", "The curriculum could not be created.", e.getMessage());
			return null;
		} catch (SQLException e) {
			showAlert(Alert.AlertType.ERROR, "New Curriculum", "The curriculum could not be stored.", e.getMessage());
			return null;
		}
	}

	private VBox createPreviewPane() {

		// Working Subject is permanent application context. Everything beneath it is
		// hosted separately so Capture and Exam/Assets can occupy the same left-hand
		// workspace without rebuilding or duplicating Subject selection.
		captureWorkspaceModePane = createCaptureWorkspaceModePane();
		workspaceModeHost = new StackPane(captureWorkspaceModePane);
		workspaceModeHost.setId("workspace-mode-host");
		VBox previewPane = new VBox(SECTION_SPACING, curriculumSelectorPane, workspaceModeHost);
		previewPane.setPadding(PREVIEW_PANE_PADDING);
		previewPane.setMinWidth(PREVIEW_PANE_MIN_WIDTH);
		previewPane.setPrefWidth(PREVIEW_PANE_INITIAL_WIDTH);

		// Initialise the banner from the same persisted booklet state used by
		// Question and Answer capture.
		refreshActiveExamContext();
		return previewPane;
	}

	private ScrollPane createPreviewScrollPane() {
		ScrollPane scrollPane = new ScrollPane(createPreviewPane());
		scrollPane.setFitToWidth(true);
		scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		scrollPane.setMinHeight(0);
		scrollPane.setMinWidth(PREVIEW_PANE_MIN_WIDTH);
		scrollPane.setPrefWidth(PREVIEW_PANE_INITIAL_WIDTH);
		return scrollPane;
	}

	private Menu createQuestionMenu(Stage primaryStage, ApplicationConfig config) {
		Menu questionMenu = createMenu("_Questions");

		// Capture modes are selected directly in the visible Question pane.
		// Keep this menu for operations that open separate question workflows.
		MenuItem searchItem = createMenuItem("_Search...", () -> showQuestionSearch(primaryStage, config));
		MenuItem corpusAuditItem = createMenuItem("_Corpus Audit...",
				() -> showQuestionCorpusAudit(primaryStage, config));
		corpusAuditItem.setId("question-corpus-audit");
		questionMenu.getItems().addAll(searchItem, corpusAuditItem);
		return questionMenu;
	}

	private RevisionExportService createRevisionExportService(ApplicationConfig config) {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteQuestionRepository revisionQuestionRepository = new SqliteQuestionRepository(database);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(revisionQuestionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		SqliteQuestionOutputApplicabilityRepository outputApplicabilityRepository = new SqliteQuestionOutputApplicabilityRepository(
				database);

		// Retrieval establishes every curriculum-derived current placement. The
		// output-applicability repository then removes only explicit Question-specific
		// exceptions while constructing the revision corpus.
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService,
				outputApplicabilityRepository);
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		QuestionExtractor extractor = new QuestionExtractor();
		return new RevisionExportService(corpusBuilder, new RevisionPresentationPlanner(),
				new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionSharedContextAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
	}

	private BorderPane createRootLayout(Stage primaryStage, ApplicationConfig config) {
		BorderPane root = new BorderPane();
		root.setTop(createMenuBar(primaryStage, config));
		previewScrollPane = createPreviewScrollPane();
		workspaceSplitPane = new SplitPane(previewScrollPane, pdfWorkspace);
		workspaceSplitPane.setId("workspace-split-pane");
		workspaceSplitPane.setDividerPositions(INITIAL_WORKSPACE_DIVIDER_POSITION);
		root.setCenter(workspaceSplitPane);
		return root;
	}

	private ScormExportService createScormExportService(ApplicationConfig config) {
		return new ScormExportService(createRevisionExportService(config), new ScormManifestWriter(),
				new ScormSchemaSupport(), new ScormPackageValidator(), new ScormZipWriter());
	}

	private void editCorpusQuestionMetadata(Stage primaryStage, ApplicationConfig config, Question question,
			Runnable completedHandler) {
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
		}
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(database);
		LegacyQuestionMetadataDialog metadataDialog = new LegacyQuestionMetadataDialog(primaryStage, question);
		Optional<LegacyQuestionMetadataDialog.Result> result = metadataDialog.showAndWait();
		if (result.isEmpty()) {
			completedHandler.run();
			return;
		}
		LegacyQuestionMetadataDialog.Result replacement = result.get();
		try {
			LegacyQuestionMetadataUpdateResult updateResult = metadataService.updateMetadataWithResult(question,
					replacement.questionCode(), replacement.marks(), question.getClassification(),
					replacement.sharedContextCaptureRequired(), replacement.responseType());
			Question updated = updateResult.question();
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			if (updateResult
					.sharedContextOutcome() == LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS) {
				offerQuestionRecaptureAfterSharedContextConversion(primaryStage, updated, completedHandler);
				return;
			}
			completedHandler.run();
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showAlert(Alert.AlertType.ERROR, "Edit Question Metadata", "The question metadata could not be saved.",
					exception.getMessage());
			completedHandler.run();
		}
	}

	private void editQuestionMetadata(Stage primaryStage, QuestionSearchDialog searchDialog, Question question,
			CurriculumRepository curriculumRepository, LegacyQuestionMetadataService metadataService) {
		LegacyQuestionMetadataDialog metadataDialog = new LegacyQuestionMetadataDialog(primaryStage, question);
		Optional<LegacyQuestionMetadataDialog.Result> result = metadataDialog.showAndWait();
		if (result.isEmpty()) {
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
			return;
		}
		LegacyQuestionMetadataDialog.Result replacement = result.get();
		try {

			// Edit Metadata deliberately preserves curriculum classification. Teachers
			// use Edit Question when the stored classification itself is incorrect.
			LegacyQuestionMetadataUpdateResult updateResult = metadataService.updateMetadataWithResult(question,
					replacement.questionCode(), replacement.marks(), question.getClassification(),
					replacement.sharedContextCaptureRequired(), replacement.responseType());
			Question updated = updateResult.question();
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			if (updateResult
					.sharedContextOutcome() == LegacyQuestionMetadataUpdateResult.SharedContextOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS) {
				offerQuestionRecaptureAfterSharedContextConversion(primaryStage, searchDialog, updated,
						curriculumRepository, metadataService);
				return;
			}
			resumeSearchAfterEdit(primaryStage, searchDialog, updated.getId(), curriculumRepository, metadataService);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showAlert(Alert.AlertType.ERROR, "Edit Question Metadata", "The question metadata could not be saved.",
					exception.getMessage());
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
		}
	}

	private void failRevisionExport(Task<RevisionExportResult> task, Alert progressAlert) {
		finishRevisionExport();
		progressAlert.close();
		showAlert(Alert.AlertType.ERROR, "Export Revision HTML", "The revision export could not be completed.",
				failureMessage(task.getException()));
	}

	private void failScormExport(Task<ScormExportResult> task, Alert progressAlert) {
		finishScormExport();
		progressAlert.close();
		showAlert(Alert.AlertType.ERROR, "Export Revision SCORM", "The SCORM export could not be completed.",
				failureMessage(task.getException()));
	}

	private String failureMessage(Throwable failure) {
		StringBuilder message = new StringBuilder();
		Throwable cause = failure;
		while (cause != null) {
			String causeMessage = cause.getMessage();
			if (causeMessage != null && !causeMessage.isBlank()) {
				if (!message.isEmpty()) {
					message.append("\n\n");
				}
				message.append(causeMessage);
			}
			cause = cause.getCause();
		}
		if (message.isEmpty()) {
			return "An unexpected error occurred.";
		}
		return message.toString();
	}

	private void failWorkingSubjectCaptureRefresh(Subject subject, long generation, Throwable failure) {
		if (!isCurrentWorkingSubjectRefresh(subject, generation)) {

			// Failure from obsolete work is irrelevant to the current Subject and must not
			// interrupt the teacher with a stale error.
			return;
		}
		String message = failure == null ? "Subject-dependent data could not be loaded." : failureMessage(failure);

		// Curriculum choices and both queues were cleared before loading began, so a
		// failure cannot leave data from the previous Subject presented as current.
		showAlert(Alert.AlertType.ERROR, "Working Subject", "Data for the Working Subject could not be loaded.",
				message);
	}

	private SharedQuestionContext findExistingSplitSharedContext(Question originalQuestion) {
		SourceQuestion matchingSource = null;
		SharedQuestionContext matchingContext = null;
		for (Question candidate : questionRepository.findAll()) {
			if (candidate.getBooklet().getId() != originalQuestion.getBooklet().getId()) {
				continue;
			}
			if (!candidate.hasSourceQuestion()) {
				continue;
			}
			SourceQuestion candidateSource = candidate.getSourceQuestion();
			if (!candidateSource.getSourceQuestionCode().equals(originalQuestion.getQuestionCode())) {
				continue;
			}
			if (matchingSource != null && matchingSource.getId() != candidateSource.getId()) {

				// The booklet-scoped source code is expected to identify one persisted
				// SourceQuestion only.
				throw new IllegalStateException(
						"More than one SourceQuestion uses source code " + originalQuestion.getQuestionCode());
			}
			matchingSource = candidateSource;
			if (!candidate.hasSharedContext()) {
				continue;
			}
			SharedQuestionContext candidateContext = candidate.getSharedContext();
			if (matchingContext != null && matchingContext.getId() != candidateContext.getId()) {
				throw new IllegalStateException("Existing source Question " + originalQuestion.getQuestionCode()
						+ " has inconsistent shared context links");
			}
			matchingContext = candidateContext;
		}
		if (matchingSource == null) {

			// No existing multipart group is available for reuse.
			return null;
		}
		if (matchingSource.getSharedContextStatus() == SharedContextStatus.UNKNOWN) {
			throw new IllegalArgumentException("Existing source Question " + originalQuestion.getQuestionCode()
					+ " still has unresolved shared context status");
		}
		if (matchingSource.getSharedContextStatus() == SharedContextStatus.NONE) {
			if (matchingContext != null) {
				throw new IllegalStateException(
						"Existing no-shared-context source Question unexpectedly uses shared context");
			}
			return null;
		}
		if (matchingContext == null) {
			throw new IllegalStateException("Existing source Question " + originalQuestion.getQuestionCode()
					+ " is recorded as having a shared context, but no shared context could be found");
		}
		return matchingContext;
	}

	private void finishRevisionExport() {
		revisionExportRunning = false;
		if (revisionExportMenuItem != null) {
			revisionExportMenuItem.setDisable(false);
		}
	}

	private void finishScormExport() {
		scormExportRunning = false;
		if (scormExportMenuItem != null) {
			scormExportMenuItem.setDisable(false);
		}
	}

	private void focusStoredQuestionRegions(Question question) {
		List<PdfWorkspacePane.RegionSelection> regions = question.getRegions().stream()
				.map(region -> new PdfWorkspacePane.RegionSelection(PdfWorkspacePane.DocumentMode.EXAM,
						region.pageNumber(), region.x(), region.y(), region.width(), region.height()))
				.toList();

		// Stored Question regions are shown only while the full Question editor owns
		// the workspace. An empty list also clears any stale previous overlay.
		pdfWorkspace.focusStoredRegions(regions);
	}

	private void handleCloseRequest(javafx.stage.WindowEvent event, Stage primaryStage) {
		event.consume();
		requestApplicationExit(primaryStage);
	}

	private void handleExamAssetsBookletMetadataUpdated(ExamBooklet updatedBooklet) {
		if (updatedBooklet == null) {
			throw new NullPointerException("updatedBooklet");
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null || activeBooklet.getId() != updatedBooklet.getId()) {

			// Editing another booklet must not disturb the current Capture target.
			return;
		}

		// Replace the active immutable booklet snapshot so Name, Type and Expected
		// Questions agree immediately with persistence.
		examMetadataPane.refreshActiveBookletPlanning(updatedBooklet);

		// A Type change affects defaults for future Question capture from this booklet.
		questionCapturePane.refreshForActiveBooklet();
		refreshActiveExamContext();
	}

	private void handlePdfSelectionInvalidated() {
		CaptureSelectionOwner owner = captureSelectionState.getOwner();
		if (owner == null) {
			return;
		}

		// Route cancellation back through the workflow that owns the logical
		// selection. Its ordinary clear path also clears CaptureSelectionState.
		//
		// PdfWorkspacePane.clearSelection() itself never invokes the cancellation
		// callback, so these programmatic clear operations cannot recurse.
		switch (owner) {
		case QUESTION -> questionCapturePane.clearCurrentSelection();
		case SHARED_CONTEXT -> questionCapturePane.clearSharedContextCurrentSelection();
		case ANSWER -> {
			answerCapturePane.clearCurrentSelectionForPageChange();
			clearCaptureSelection(CaptureSelectionOwner.ANSWER);
		}
		}
	}

	private void handleRegionSelection(PdfWorkspacePane.RegionSelection selection) {
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.ANSWER) {

			// The newly completed Answer rectangle becomes the application's single pending
			// selection before AnswerCapturePane receives it.
			claimCaptureSelection(CaptureSelectionOwner.ANSWER);
			answerCapturePane.acceptSelection(selection);
			return;
		}
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.EXAM) {
			if (questionCapturePane.isCapturingSharedContext()) {

				// Shared-context capture owns this Exam-PDF rectangle and supersedes any
				// incompatible pending selection from another workflow.
				claimCaptureSelection(CaptureSelectionOwner.SHARED_CONTEXT);
				questionCapturePane.acceptSharedContextSelection(selection);
			} else {

				// Ordinary Question capture owns this Exam-PDF rectangle and supersedes any
				// incompatible pending selection from another workflow.
				claimCaptureSelection(CaptureSelectionOwner.QUESTION);
				questionCapturePane.acceptSelection(selection);
			}
		}
	}

	private void handleSubjectChanged(Subject newSubject) {
		if (restoringWorkingSubject) {
			return;
		}
		boolean subjectValueChanged = !Objects.equals(workingSubject, newSubject);
		boolean guardedSubjectChange = workingSubject != null && subjectValueChanged;
		if (guardedSubjectChange && !allowWorkingSubjectChange()) {

			// The application-managed Subject handler has not cleared curriculum,
			// Question, Answer or PDF state because the transition was rejected before
			// the asynchronous refresh began.
			Platform.runLater(() -> {
				restoringWorkingSubject = true;
				try {

					// Restore only the accepted Subject value. Existing classification
					// and PDF state were deliberately preserved by the rejected change.
					curriculumSelectorPane.selectSubject(workingSubject);
				} finally {
					restoringWorkingSubject = false;
				}
			});
			return;
		}

		// Working Subject becomes authoritative synchronously. Persistence-heavy
		// rebuilding of Subject-dependent data is deferred to the application worker.
		workingSubject = newSubject;
		if (subjectValueChanged) {

			// Pending legacy preflight belongs to the previous authoritative Subject
			// and cannot survive an accepted application-level Subject transition.
			cancelPendingLegacyQuestionImport();

			// The PDF workspace is Subject-dependent application context. Remove any
			// document that still belongs to the previous Subject before replacement
			// state begins loading.
			clearPdfWorkspaceForSubjectChange(newSubject);
		}

		// Curriculum, Question/Answer and visible Exam/Assets share one asynchronous
		// generation so stale work cannot overwrite a later Subject selection.
		startWorkingSubjectRefresh(newSubject);
		examMetadataPane.invalidateForSubjectChange(newSubject);

		// Moving application Subject may invalidate an active Exam from the old
		// Subject, so this lightweight visible context changes immediately.
		refreshActiveExamContext();
	}

	private AnswerFile importAnswerBookletFromExamAssets(Exam exam, Path sourcePath, String name,
			boolean containsAnswerExplanations, ApplicationConfig config) throws IOException, SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Answer booklet name must not be blank");
		}
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamWriter writer = new SqliteExamWriter(database);

		// Cross-type duplication is also unsafe: an Answer add must not silently reuse
		// bytes already managed as a Question booklet or another Answer asset.
		String sourceHash = requireNewManagedPdfContent(sourcePath, writer, "Answer booklet");
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path storedPath = pdfStore.importExamPdf(sourcePath, exam.getSubject().getName(), exam.getProvider().getName(),
				exam.getYear());
		String storedHash = new SourceDocumentHashService().sha256(storedPath);
		if (!sourceHash.equals(storedHash)) {

			// Do not register an AnswerFile if the managed bytes no longer match the
			// source that passed duplicate detection.
			throw new IOException("Answer booklet PDF changed while it was being copied");
		}
		Path pdfRoot = config.pdfDataRoot().toAbsolutePath().normalize();
		String relativePath = pdfRoot.relativize(storedPath.toAbsolutePath().normalize()).toString();
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, writer);

		// Creation and explanation metadata are persisted only after duplicate and byte
		// integrity checks have succeeded.
		return answerWriter.findOrCreateAnswerFile(exam, name.strip(), relativePath, storedHash,
				containsAnswerExplanations);
	}

	private void importCurriculum(Stage primaryStage, ApplicationConfig config) {
		CurriculumImportDialog dialog = new CurriculumImportDialog(primaryStage, config.curriculumDataRoot());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty()) {
			return;
		}
		if (result.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		try {
			CurriculumExcelImporter excelImporter = new CurriculumExcelImporter();
			List<CurriculumImportRow> rows = excelImporter.read(dialog.getSelectedFile());
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
			SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
			CurriculumImportResult importResult = importer.importSyllabusWithResult(dialog.getSubjectName(),
					dialog.getVersionName(), dialog.isCurrent(), rows);
			curriculumSelectorPane.refreshSubjects();
			examMetadataPane.refreshSubjects();
			if (importResult.imported()) {
				showAlert(Alert.AlertType.INFORMATION, "Curriculum Import", "Curriculum imported successfully.",
						dialog.getSubjectName() + " " + dialog.getVersionName());
			} else {
				showAlert(Alert.AlertType.INFORMATION, "Curriculum Import", "Curriculum already imported.",
						dialog.getSubjectName() + " " + dialog.getVersionName()
								+ " is already imported. No changes were required.");
			}
		} catch (IOException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Import", "Could not read the Excel file.", e.getMessage());
		} catch (CurriculumImportConflictException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Import", "Could not import curriculum.", e.getMessage());
		} catch (SQLException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Import", "Could not save the curriculum.", e.getMessage());
		} catch (IllegalArgumentException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Import", "The curriculum file is invalid.", e.getMessage());
		}
	}

	private void importLegacyQuestionMetadata(Stage primaryStage, ApplicationConfig config) {
		if (workingSubject == null) {

			// Legacy intake cannot establish its own Subject.
			showAlert(Alert.AlertType.WARNING, "Legacy Question Import", "No Working Subject is selected.",
					"Select a Working Subject before importing legacy Question metadata.");
			return;
		}
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
			Subject subject = workingSubject;
			LegacyQuestionImportDialog dialog = new LegacyQuestionImportDialog(primaryStage, subject,
					curriculumRepository);
			Optional<ButtonType> result = dialog.showAndWait();
			if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
				return;
			}

			// Freeze the complete intake context before preflight. Subsequent Exam/Assets
			// editing must not alter which Subject, syllabus or workbook is being imported.
			PendingLegacyQuestionImport pending = new PendingLegacyQuestionImport(subject,
					dialog.getSelectedSyllabusVersion(), dialog.getSelectedFile());
			continueLegacyQuestionImport(primaryStage, config, pending);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "The legacy Question import could not start.",
					exception.getMessage());
		}
	}

	private ExamBooklet importQuestionBookletFromExamAssets(Exam exam, Path sourcePath, String name,
			ExamBookletQuestionFormat questionFormat, Integer expectedQuestionCount, ApplicationConfig config)
			throws IOException, SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		if (questionFormat == null) {
			throw new NullPointerException("questionFormat");
		}
		if (config == null) {
			throw new NullPointerException("config");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Question booklet name must not be blank");
		}
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamWriter writer = new SqliteExamWriter(database);

		// Detect byte-identical managed material before PdfStore creates another
		// managed
		// file with a different filename.
		String sourceHash = requireNewManagedPdfContent(sourcePath, writer, "Question booklet");
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path storedPath = pdfStore.importExamPdf(sourcePath, exam.getSubject().getName(), exam.getProvider().getName(),
				exam.getYear());
		String storedHash = new SourceDocumentHashService().sha256(storedPath);
		if (!sourceHash.equals(storedHash)) {

			// The selected bytes changed between duplicate detection and managed copying.
			// Do not publish a SourceDocument identity based on inconsistent evidence.
			throw new IOException("Question booklet PDF changed while it was being copied");
		}
		Path pdfRoot = config.pdfDataRoot().toAbsolutePath().normalize();
		String relativePath = pdfRoot.relativize(storedPath.toAbsolutePath().normalize()).toString();
		SqliteExamImporter importer = new SqliteExamImporter(database, writer);

		// Persistence receives the verified final managed-byte identity.
		return importer.importExam(exam.getSubject(), exam.getProvider().getName(), exam.getYear(), exam.getName(),
				name.strip(), relativePath, questionFormat, expectedQuestionCount, storedHash);
	}

	private void initialiseCaptureWorkflow(Stage primaryStage, ApplicationConfig config, SqliteDatabase database,
			PdfFilePicker answerPdfPicker) {
		questionRepository = new SqliteQuestionRepository(database);

		// Curriculum Subject state is persistence-only at this boundary and can be
		// safely loaded by the application worker before JavaFX publication.
		workingSubjectCurriculumSnapshotLoader = curriculumSelectionModel::loadSubjectSnapshot;

		// Working Subject transitions load the corpus once at application level and
		// then publish that same immutable snapshot to Question and Answer capture.
		workingSubjectQuestionSnapshotLoader = questionRepository::findAll;
		SourceQuestionRepository sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		SqliteQuestionCaptureService questionCaptureService = new SqliteQuestionCaptureService(database);
		LegacyQuestionSplitService legacyQuestionSplitService = new LegacyQuestionSplitService(database);

		// Retain the assessment writer because booklet inspection later records
		// structural planning against the same authoritative repository.
		examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);

		// Exam correction owns both filesystem relocation and the atomic Exam /
		// SourceDocument database update.
		// Both the transitional capture metadata pane and the new Exam/Assets workspace
		// use one set of reusable metadata suggestions.
		ExamMetadataOptionsRepository examMetadataOptionsRepository = new ExamMetadataOptionsRepository();
		ExamMetadataCorrectionService examMetadataCorrectionService = new ExamMetadataCorrectionService(
				config.pdfDataRoot(), examWriter, answerWriter);
		examMetadataPane = new ExamMetadataPane(primaryStage, config.pdfDataRoot(), curriculumSelectionModel,
				examMetadataOptionsRepository, examImporter, examWriter, examMetadataCorrectionService,
				this::allowExamImportConfirmation, this::openExamPdf, pdfWorkspace::setSelectionCursorEnabled,
				this::activateExamBookletSubject);

		// The new main-window Exam/Assets workspace reads the same authoritative
		// repositories as the transitional modal Exam Setup workflow.
		// The new workspace owns Exam editing presentation while the application
		// retains
		// responsibility for correction, PDF-session handling and capture-state
		// refresh.
		// Returning to Capture is no longer coupled to Cancel. The later explicit
		// Use Selected Booklet for Capture action will own that transition.
		// ExamAssetsPane owns presentation while the application owns managed-path
		// resolution and the shared PDF workspace.
		examAssetsPane = new ExamAssetsPane(examWriter, answerWriter, examMetadataOptionsRepository,
				this::correctExamMetadataAndReloadCapture,
				booklet -> viewQuestionBookletFromExamAssets(booklet, config),
				answerFile -> viewAnswerFileFromExamAssets(answerFile, config), examMetadataPane::getBooklet,
				booklet -> useExamAssetsBookletForCapture(booklet, config),
				this::handleExamAssetsBookletMetadataUpdated,

				// Native file selection remains application-owned rather than being
				// embedded in the Exam/Assets presentation component.
				() -> chooseAnswerBookletSource(primaryStage, config),

				// The application imports the chosen PDF into managed storage, hashes
				// it and persists the AnswerFile plus its explanation metadata.
				(exam, sourcePath, name, containsAnswerExplanations) -> importAnswerBookletFromExamAssets(exam,
						sourcePath, name, containsAnswerExplanations, config),

				// Native Question-PDF selection remains application-owned.
				() -> chooseQuestionBookletSource(primaryStage, config),

				// The selected pre-persistence source opens immediately in read-only VIEWER
				// mode.
				this::viewPendingQuestionBookletSourceFromExamAssets,

				// Cancelling or completing the pending transaction restores the prior PDF view.
				this::closePendingQuestionBookletSourceFromExamAssets,

				// Save imports, hashes and persists the managed Question booklet.
				(exam, sourcePath, name, questionFormat, expectedQuestionCount) -> importQuestionBookletFromExamAssets(
						exam, sourcePath, name, questionFormat, expectedQuestionCount, config),

				// Legacy intake is launched from Exam/Assets but remains coordinated by
				// the application because it spans dialogs, persistence and capture state.
				() -> importLegacyQuestionMetadata(primaryStage, config));
		workingSubjectExamAssetsSnapshotLoader = subject -> {
			try {

				// This function is invoked only by the application-owned worker task;
				// the pane method itself performs persistence reads but no JavaFX work.
				return examAssetsPane.loadSubjectSnapshot(subject);
			} catch (SQLException exception) {

				// Function cannot declare SQLException. Preserve it as the cause so the
				// FX-thread failure handler can present the underlying persistence error.
				throw new IllegalStateException("Exam assets could not be loaded.", exception);
			}
		};

		// The selector receives application-level Subject creation through the same
		// database used by the rest of the capture workflow.
		curriculumSelectorPane = createCurriculumSelectorPane(primaryStage, database);
		answerCapturePane = new AnswerCapturePane(primaryStage, questionRepository, answerWriter, answerPdfPicker,
				this::openAnswerPdf, () -> pdfWorkspace.showDocument(PdfWorkspacePane.DocumentMode.ANSWER),
				this::allowAnswerCaptureTransition, examMetadataPane::getBooklet,
				() -> clearCaptureSelection(CaptureSelectionOwner.ANSWER), questionExtractor,
				pdfWorkspace::getAnswerPdfSession,
				pageNumber -> pdfWorkspace.showPage(PdfWorkspacePane.DocumentMode.ANSWER, pageNumber),
				(selected, completed) -> pdfWorkspace.openAnswerPdfAsync(selected.path(), completed));
		answerCapturePane.refreshQuestions();
		SharedContextCapturePane sharedContextCapturePane = new SharedContextCapturePane(
				new SqliteSharedQuestionContextRepository(database), examMetadataPane::getBooklet, questionExtractor,
				pdfWorkspace::getExamPdfSession, () -> clearCaptureSelection(CaptureSelectionOwner.SHARED_CONTEXT),
				() -> !captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		questionCapturePane = new QuestionCapturePane(questionRepository, sourceQuestionRepository,
				questionCaptureService, legacyQuestionSplitService, sharedContextCapturePane, questionExtractor,
				curriculumSelectionModel, curriculumSelectorPane, examMetadataPane::getBooklet,
				pdfWorkspace::getExamPdfSession, question -> activateImportedQuestion(question, config),
				pageNumber -> pdfWorkspace.showPage(PdfWorkspacePane.DocumentMode.EXAM, pageNumber),
				this::confirmDiscardAcceptedQuestionRegions, this::transferQuestionSelectionToSharedContext,
				() -> clearCaptureSelection(CaptureSelectionOwner.QUESTION), answerCapturePane::refreshQuestions);
		questionCapturePane.refreshImportedQuestions();
	}

	private boolean isCurrentWorkingSubjectRefresh(Subject subject, long generation) {

		// Both generation and Subject identity must still describe the currently
		// accepted application context.
		return generation == workingSubjectRefreshGeneration && Objects.equals(workingSubject, subject);
	}

	private boolean isRegionSelectionAvailable(PdfWorkspacePane.DocumentMode documentMode) {
		if (documentMode == PdfWorkspacePane.DocumentMode.VIEWER) {
			return false;
		}
		if (documentMode == PdfWorkspacePane.DocumentMode.ANSWER) {
			return answerCapturePane.canCaptureRegions();
		}

		// An active booklet alone no longer implies that Question capture has begun.
		return examMetadataPane.getBooklet() != null && questionCapturePane.canCaptureRegions();
	}

	private void markActiveExamComplete(Stage primaryStage) {
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Mark Exam Complete", "No Exam is active.",
					"Open an Exam booklet before marking its Exam complete.");
			return;
		}
		if (activeBooklet.getExam().isComplete()) {
			showAlert(Alert.AlertType.INFORMATION, "Mark Exam Complete", "This Exam is already complete.",
					"Reactivate it if structural correction is required.");
			return;
		}
		if (!allowExamLifecycleChange()) {
			return;
		}
		ButtonType completeButton = new ButtonType("Mark Complete", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
		confirmation.initOwner(primaryStage);
		confirmation.setTitle("Mark Exam Complete");
		confirmation.setHeaderText("Mark this Exam complete?");
		confirmation.setContentText("""
				Completing the Exam locks structural changes such as:

				• adding Question booklets or Questions
				• changing Question or Answer source PDFs
				• changing booklet format or expected counts
				• changing Question structural identity
				• adding or reassigning Answer files

				Ordinary corrections to marks, classifications, regions, Answers and Shared Context remain available.

				Use Reactivate Exam if structural correction is later required.
				""");
		confirmation.getButtonTypes().setAll(completeButton, cancelButton);
		if (confirmation.showAndWait().orElse(cancelButton) != completeButton) {
			return;
		}
		try {
			examMetadataPane.setActiveExamCaptureState(ExamCaptureState.COMPLETE);

			// Lifecycle is part of the visible active-Exam context.
			refreshActiveExamContext();
			showAlert(Alert.AlertType.INFORMATION, "Exam State", "Exam marked complete.",
					"Structural changes now require Reactivate Exam.");
		} catch (SQLException | RuntimeException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam State", "The Exam state could not be changed.",
					failureMessage(exception));
		}
	}

	private void offerQuestionRecaptureAfterSharedContextConversion(Stage primaryStage, Question question,
			Runnable completedHandler) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (completedHandler == null) {
			throw new NullPointerException("completedHandler");
		}
		ButtonType recaptureButton = new ButtonType("Recapture complete question", ButtonBar.ButtonData.OK_DONE);
		ButtonType keepButton = new ButtonType("Keep converted regions", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(primaryStage);
		alert.setTitle("Question Shared Context Converted");
		alert.setHeaderText("The captured shared context has been converted to ordinary question regions.");
		alert.setContentText(
				"""
						The converted material has already been saved safely.

						You can now recapture the complete question as one or more replacement regions, or keep the converted regions as they are.
						""");
		alert.getButtonTypes().setAll(recaptureButton, keepButton);
		ButtonType decision = alert.showAndWait().orElse(keepButton);
		if (decision != recaptureButton) {
			completedHandler.run();
			return;
		}
		boolean recaptureStarted = questionCapturePane.recaptureQuestion(question, () -> {

			// Recapture explicitly opened the source Exam PDF. Once the
			// recapture is saved or cancelled, that temporary document must
			// no longer remain active.
			pdfWorkspace.closeExamPdf();
			completedHandler.run();
		});
		if (!recaptureStarted) {
			completedHandler.run();
		}
	}

	private void offerQuestionRecaptureAfterSharedContextConversion(Stage primaryStage,
			QuestionSearchDialog searchDialog, Question question, CurriculumRepository curriculumRepository,
			LegacyQuestionMetadataService metadataService) {
		Runnable resumeSearch = () -> resumeSearchAfterEdit(primaryStage, searchDialog, question.getId(),
				curriculumRepository, metadataService);
		offerQuestionRecaptureAfterSharedContextConversion(primaryStage, question, resumeSearch);
	}

	private void openAnswerPdf(SelectedPdf selectedPdf) {
		pdfWorkspace.openAnswerPdf(selectedPdf.path());
	}

	private void openCurriculumAuthoringWindow(Stage primaryStage, ApplicationConfig config, SqliteDatabase database,
			CurriculumAuthoringSession session) {

		// Windows are the registry: hiding/closing releases access automatically,
		// while a cancelled close retains it. Include FINAL views that can reopen.
		for (Window window : List.copyOf(Window.getWindows())) {
			if (window instanceof Stage existing && existing.getOwner() == primaryStage && existing.getScene() != null
					&& existing.getScene().getRoot() instanceof CurriculumAuthoringPane pane
					&& pane.isForSyllabus(session.syllabusVersion().getId())) {
				existing.toFront();
				Alert alert = new Alert(Alert.AlertType.INFORMATION);
				alert.initOwner(existing);
				alert.setTitle("Curriculum Authoring");
				alert.setHeaderText("This curriculum is already open for editing.");
				alert.setContentText("Use the existing authoring window, or close it before opening another.");
				alert.showAndWait();
				return;
			}
		}
		SqliteCurriculumAuthoringWriter authoringWriter = new SqliteCurriculumAuthoringWriter(database);
		CurriculumSourcePdfService sourcePdfService = new CurriculumSourcePdfService(
				new CurriculumSourcePdfStore(config.curriculumDataRoot()),
				new SqliteCurriculumSourcePdfRepository(database));
		CurriculumLifecycleService lifecycleService = new CurriculumLifecycleService(authoringWriter,
				new SqliteCurriculumLifecycleRepository(database), Clock.systemUTC());
		SyllabusVersion syllabusVersion = session.syllabusVersion();
		Stage authoringStage = new Stage();
		authoringStage.initOwner(primaryStage);
		authoringStage.setTitle(
				"Curriculum Authoring — " + syllabusVersion.getSubject().getName() + " " + syllabusVersion.getName());
		CurriculumAuthoringPane authoringPane = new CurriculumAuthoringPane(authoringStage, config.curriculumDataRoot(),
				session, authoringWriter, sourcePdfService, lifecycleService);
		authoringStage.setScene(new Scene(authoringPane, AUTHORING_WINDOW_WIDTH, AUTHORING_WINDOW_HEIGHT));
		authoringStage.setOnCloseRequest(event -> {
			if (!confirmCurriculumAuthoringClose(authoringStage, authoringPane)) {
				event.consume();
			}
		});
		authoringStage.setOnHidden(_ -> {
			try {
				authoringPane.close();
			} catch (Exception e) {
				showAlert(Alert.AlertType.WARNING, "Curriculum Authoring",
						"The curriculum authoring workspace could not be closed cleanly.", e.getMessage());
			} finally {
				curriculumSelectorPane.refreshSubjects();
				examMetadataPane.refreshSubjects();
			}
		});
		authoringStage.show();
	}

	private void openExamPdf(SelectedPdf selectedPdf) {
		pdfWorkspace.openExamPdf(selectedPdf.path());
		questionCapturePane.clearForNewPdf();
	}

	private void openViewerPdf(Stage primaryStage, ApplicationConfig config) {
		PdfFilePicker picker = new PdfFilePicker(config.pdfDataRoot());
		Path selectedPath = picker.chooseAnyPdf(primaryStage, "Open PDF");
		if (selectedPath == null) {
			return;
		}
		pdfWorkspace.openViewerPdf(selectedPath);
		setViewerMode(true);
	}

	private Window primaryWindow() {
		if (workspaceSplitPane == null || workspaceSplitPane.getScene() == null
				|| workspaceSplitPane.getScene().getWindow() == null) {
			throw new IllegalStateException("Primary application window is not available");
		}

		// Resolve ownership from the live scene graph rather than retaining another
		// Stage reference solely for Help presentation.
		return workspaceSplitPane.getScene().getWindow();
	}

	private void reactivateActiveExam() {
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Reactivate Exam", "No Exam is active.",
					"Open an Exam booklet before reactivating its Exam.");
			return;
		}
		if (!activeBooklet.getExam().isComplete()) {
			showAlert(Alert.AlertType.INFORMATION, "Reactivate Exam", "This Exam is already active.",
					"No lifecycle change is required.");
			return;
		}
		if (!allowExamLifecycleChange()) {
			return;
		}
		try {
			examMetadataPane.setActiveExamCaptureState(ExamCaptureState.ACTIVE);

			// Lifecycle is part of the visible active-Exam context.
			refreshActiveExamContext();
			showAlert(Alert.AlertType.INFORMATION, "Exam State", "Exam reactivated.",
					"Structural correction is available again.");
		} catch (SQLException | RuntimeException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam State", "The Exam state could not be changed.",
					failureMessage(exception));
		}
	}

	private void recheckPendingLegacyQuestionImport(Stage primaryStage, ApplicationConfig config) {
		PendingLegacyQuestionImport pending = pendingLegacyQuestionImport;
		if (pending == null) {

			// A stale UI event has nothing left to resume.
			return;
		}

		// Recheck executes the same authoritative preflight as the initial intake.
		// Saving an Exam or booklet never bypasses validation.
		continueLegacyQuestionImport(primaryStage, config, pending);
	}

	private void refreshActiveExamContext() {
		changeExamAssetsButton.setDisable(workingSubject == null);
		if (examMetadataPane == null) {
			activeExamBookletLabel.setText("No Exam booklet selected");
			return;
		}
		ExamBooklet booklet = examMetadataPane.getBooklet();
		if (booklet == null) {
			activeExamBookletLabel.setText("No Exam booklet selected");
			return;
		}
		Exam exam = booklet.getExam();
		String lifecycle = exam.isComplete() ? "COMPLETE" : "ACTIVE";

		// Keep the active structural context visible independently of Question
		// classification and Answer workflow state.
		activeExamBookletLabel.setText("%s %d %s — %s [%s]".formatted(exam.getProvider().getName(), exam.getYear(),
				exam.getName(), booklet.getName(), lifecycle));
	}

	private void replaceActiveAnswerPdf(Stage primaryStage, ApplicationConfig config) {
		if (!allowAnswerPdfReplacement()) {
			return;
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Replace Answer PDF", "No Exam booklet is active.",
					"Open the Exam booklet whose Answer PDF you want to correct.");
			return;
		}
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		try {
			if (answerWriter.findAnswerFile(activeBooklet) == null) {
				showAlert(Alert.AlertType.WARNING, "Replace Answer PDF", "No Answer PDF is assigned to this booklet.",
						"Assign an Answer PDF through Answer capture before using the replacement workflow.");
				return;
			}
		} catch (SQLException exception) {
			showAlert(Alert.AlertType.ERROR, "Replace Answer PDF", "The assigned Answer PDF could not be read.",
					exception.getMessage());
			return;
		}
		PdfFilePicker picker = new PdfFilePicker(config.pdfDataRoot());
		Path replacementPath = picker.chooseAnyPdf(primaryStage, "Choose replacement Answer PDF");
		if (replacementPath == null) {
			return;
		}

		// Keep native file selection out of the destructive method so workflow tests
		// can
		// exercise the real confirmation and persistence path deterministically.
		replaceActiveAnswerPdf(primaryStage, config, replacementPath);
	}

	private void replaceActiveAnswerPdf(Stage primaryStage, ApplicationConfig config, Path replacementPath) {
		if (replacementPath == null) {
			throw new NullPointerException("replacementPath");
		}
		if (!allowAnswerPdfReplacement()) {
			return;
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Replace Answer PDF", "No Exam booklet is active.",
					"Open the Exam booklet whose Answer PDF you want to correct.");
			return;
		}
		AnswerPdfReplacementService replacementService = new AnswerPdfReplacementService(
				new SqliteDatabase(config.databasePath()), config.pdfDataRoot());
		try {
			AnswerFileReassignmentService.Impact impact = replacementService.assess(activeBooklet);
			if (!confirmAnswerPdfReplacement(primaryStage, activeBooklet, impact)) {
				return;
			}

			// Release any PDFBox handle on the old marking guide before changing the active
			// AnswerFile. The old managed file itself remains intact.
			pdfWorkspace.closeAnswerPdf();
			AnswerPdfReplacementService.Result result = replacementService.replace(activeBooklet, replacementPath);

			// Region-only written Answers may now be absent again. Rebuild from persistence
			// rather than retaining the pane's local "answered" suppression cache.
			answerCapturePane.refreshAfterAnswerFileCorrection();
			if (!result.changed()) {
				showAlert(Alert.AlertType.INFORMATION, "Answer PDF Replaced", "The selected PDF is already current.",
						"The selected file has the same content as the assigned Answer PDF. No Answer regions were invalidated.");
				return;
			}
			showAlert(Alert.AlertType.INFORMATION, "Answer PDF Replaced", "The Answer PDF assignment was corrected.",
					"""
							Invalidated Answer regions: %d
							Affected Questions: %d
							Answers retaining independent text: %d
							Region-only Answers returned to capture: %d

							Independent MCQ answer letters and other textual Answer content were preserved.
							""".formatted(result.impact().answerRegionCount(), result.impact().affectedQuestionCount(),
							result.impact().preservedTextAnswerCount(), result.impact().regionOnlyAnswerCount()));
		} catch (IOException | SQLException | RuntimeException exception) {
			String message = exception.getMessage();
			if (message == null || message.isBlank()) {
				message = exception.getClass().getSimpleName();
			}
			showAlert(Alert.AlertType.ERROR, "Replace Answer PDF", "The Answer PDF could not be replaced.", message);
		}
	}

	private void replaceActiveQuestionPdf(Stage primaryStage, ApplicationConfig config) {
		if (!allowQuestionPdfReplacement()) {
			return;
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Replace Question PDF", "No Exam booklet is active.",
					"Open the Exam booklet whose Question PDF you want to replace.");
			return;
		}
		PdfFilePicker picker = new PdfFilePicker(config.pdfDataRoot());
		Path replacementPath = picker.chooseAnyPdf(primaryStage, "Choose replacement Question PDF");
		if (replacementPath == null) {
			return;
		}

		// File selection and actual replacement are separated so workflow tests can
		// exercise the destructive operation without automating the native file
		// chooser.
		replaceActiveQuestionPdf(primaryStage, config, replacementPath);
	}

	private void replaceActiveQuestionPdf(Stage primaryStage, ApplicationConfig config, Path replacementPath) {
		if (replacementPath == null) {
			throw new NullPointerException("replacementPath");
		}
		if (!allowQuestionPdfReplacement()) {
			return;
		}
		ExamBooklet activeBooklet = examMetadataPane.getBooklet();
		if (activeBooklet == null) {
			showAlert(Alert.AlertType.WARNING, "Replace Question PDF", "No Exam booklet is active.",
					"Open the Exam booklet whose Question PDF you want to replace.");
			return;
		}
		QuestionBookletPdfReplacementService replacementService = new QuestionBookletPdfReplacementService(
				new SqliteDatabase(config.databasePath()), config.pdfDataRoot());
		try {
			QuestionBookletPdfReplacementService.Impact impact = replacementService.assess(activeBooklet);
			if (!confirmQuestionPdfReplacement(primaryStage, activeBooklet, impact)) {
				return;
			}

			// PDFBox may hold the managed booklet open on Windows. Close the current Exam
			// session only after the user has confirmed the destructive operation.
			pdfWorkspace.closeExamPdf();
			QuestionBookletPdfReplacementService.Result result;
			try {
				result = replacementService.replace(activeBooklet, replacementPath);
			} catch (IOException | SQLException | RuntimeException exception) {

				// The service restores old managed bytes when necessary. Reopen that
				// authoritative source so a failed replacement does not strand the workspace.
				try {
					examMetadataPane.reopenActiveExamPdf();
				} catch (RuntimeException reopenFailure) {
					exception.addSuppressed(reopenFailure);
				}
				throw exception;
			}
			Path managedPath = new PdfStore(config.pdfDataRoot())
					.resolve(result.booklet().getSourceDocument().getRelativePath());

			// Refresh the in-memory booklet before reopening the managed source so the
			// workspace and persistence expose the same SHA-256 identity.
			examMetadataPane.activateExistingBooklet(result.booklet(), managedPath);
			examMetadataPane.reopenActiveExamPdf();

			// Replacement may have removed PDF-backed Question and Shared Context
			// capture. Rebuild both work queues from the committed database state.
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			if (!result.contentChanged()) {
				showAlert(Alert.AlertType.INFORMATION, "Question PDF Replaced", "The selected PDF is already current.",
						"The selected file has the same content as the managed Question PDF. No Question or Shared Context capture was invalidated.");
				return;
			}
			showAlert(Alert.AlertType.INFORMATION, "Question PDF Replaced", "The Question PDF was replaced.", """
					Invalidated Question PDF regions: %d
					Invalidated Shared Contexts: %d
					Affected Questions: %d
					Preserved stored image parts: %d

					Question metadata, marks, classifications, response types and Answers were preserved.
					""".formatted(result.impact().pdfRegionCount(), result.impact().sharedContextCount(),
					result.impact().affectedQuestionCount(), result.impact().preservedImageCount()));
		} catch (IOException | SQLException | RuntimeException exception) {
			String message = exception.getMessage();
			if (message == null || message.isBlank()) {
				message = exception.getClass().getSimpleName();
			}
			showAlert(Alert.AlertType.ERROR, "Replace Question PDF", "The Question PDF could not be replaced.",
					message);
		}
	}

	private void requestApplicationExit(Stage primaryStage) {
		if (blockWhileCaptureSaveInProgress(primaryStage, "closing the application")) {
			return;
		}

		// Authoring windows own independent drafts. Resolve them before backup or
		// resource shutdown, which must include any curriculum saved by this prompt.
		for (Window window : List.copyOf(Window.getWindows())) {
			if (window instanceof Stage stage && stage.getOwner() == primaryStage && stage.getScene() != null
					&& stage.getScene().getRoot() instanceof CurriculumAuthoringPane pane
					&& !confirmCurriculumAuthoringClose(stage, pane)) {
				return;
			}
		}
		while (true) {
			ShutdownResult result = shutdownCoordinator.prepareForExit();
			if (result.status() == ShutdownStatus.READY_TO_EXIT_WITH_RETENTION_WARNING) {
				showRetentionWarning(primaryStage, result.failure());
				applicationExitAction.run();
				return;
			}
			if (result.exitAllowed()) {
				applicationExitAction.run();
				return;
			}
			if (result.status() == ShutdownStatus.RESOURCE_CLOSE_FAILED) {
				showResourceCloseFailure(primaryStage, result.failure());
				return;
			}
			BackupFailureDecision decision = showAutomaticBackupFailure(primaryStage, result.failure());
			if (decision == BackupFailureDecision.CANCEL_EXIT) {
				return;
			}
			if (decision == BackupFailureDecision.EXIT_WITHOUT_BACKUP) {
				completeExitWithoutBackup(primaryStage);
				return;
			}

			// RETRY deliberately loops through prepareForExit().
		}
	}

	private String requireNewManagedPdfContent(Path sourcePath, SqliteExamWriter writer, String assetDescription)
			throws IOException, SQLException {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		if (writer == null) {
			throw new NullPointerException("writer");
		}
		if (assetDescription == null || assetDescription.isBlank()) {
			throw new IllegalArgumentException("assetDescription must not be blank");
		}
		String contentSha256 = new SourceDocumentHashService().sha256(sourcePath);
		List<SourceDocument> matches = writer.findSourceDocumentsByHash(contentSha256);
		if (matches.isEmpty()) {

			// No persisted managed source currently owns these bytes, so normal intake may
			// proceed.
			return contentSha256;
		}
		String knownPaths = matches.stream().map(SourceDocument::getRelativePath).collect(Collectors.joining(", "));
		if (matches.size() == 1) {

			// Add means creation of another structural asset. Byte-identical managed
			// content must be surfaced rather than silently duplicated or reinterpreted.
			throw new IllegalArgumentException("Selected " + assetDescription + " PDF is already managed as "
					+ knownPaths + ". Use the existing asset instead.");
		}

		// More than one existing source with the same bytes is inherently ambiguous.
		// The application must never choose one by filename, row order or Exam.
		throw new IllegalArgumentException(
				"Selected " + assetDescription + " PDF matches more than one managed document: " + knownPaths
						+ ". Resolve the duplicate managed documents before adding another asset.");
	}

	private void restoreBackup(Stage primaryStage, ApplicationConfig config) {
		if (blockWhileCaptureSaveInProgress(primaryStage, "restoring a backup")) {
			return;
		}
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Restore Question-Bank Backup");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Question-bank backups (*.zip)", "*.zip"));
		File selectedFile = chooser.showOpenDialog(primaryStage);
		if (selectedFile == null) {
			return;
		}
		DefaultRestoreService restoreService = new DefaultRestoreService(config);
		RestorePreparation preparation;
		try {
			preparation = restoreService.prepareRestore(selectedFile.toPath());
		} catch (RestoreException e) {
			showAlert(Alert.AlertType.ERROR, "Restore Backup", "The selected backup cannot be restored.",
					failureMessage(e));
			return;
		}
		if (!confirmRestore(primaryStage, preparation)) {
			closeRestorePreparation(primaryStage, preparation);
			return;
		}
		RestoreResult restoreResult;
		try {
			DefaultRestoreExecutor executor = new DefaultRestoreExecutor(config, applicationVersion());
			restoreResult = executor.applyRestore(preparation, pdfWorkspace);
			resourcesClosedForRestore = true;
		} catch (RestoreException e) {
			closeRestorePreparation(primaryStage, preparation);
			showAlert(Alert.AlertType.ERROR, "Restore Backup", "The backup could not be restored.", failureMessage(e));
			if (e.applicationMustExit()) {
				showAlert(Alert.AlertType.WARNING, "Restart Required", "The application must now close.",
						"Restart Exam Question Bank before continuing.");
				applicationExitAction.run();
			}
			return;
		}
		closeRestorePreparation(primaryStage, preparation);
		showAlert(Alert.AlertType.INFORMATION, "Restore Backup", "Restore completed successfully.", """
				The restored data has been installed.

				A pre-restore safety backup was saved to:

				%s

				The application will now close. Restart Exam Question Bank to use the restored data.
				""".formatted(restoreResult.safetyBackupPath()));
		applicationExitAction.run();
	}

	private void restoreManagedPdfSessions(PdfWorkspacePane.DocumentMode displayedBeforeCorrection,
			int pageBeforeCorrection) {
		examMetadataPane.reopenActiveExamPdf();
		answerCapturePane.reopenSelectedAnswerDocument();

		// Reopening both documents can change which one is visible. Restore the
		// teacher's previous document and page after all file handles are current.
		if (displayedBeforeCorrection == PdfWorkspacePane.DocumentMode.EXAM
				&& pdfWorkspace.getExamPdfSession() != null) {
			pdfWorkspace.showPage(PdfWorkspacePane.DocumentMode.EXAM, pageBeforeCorrection);
			return;
		}
		if (displayedBeforeCorrection == PdfWorkspacePane.DocumentMode.ANSWER
				&& pdfWorkspace.getAnswerPdfSession() != null) {
			pdfWorkspace.showPage(PdfWorkspacePane.DocumentMode.ANSWER, pageBeforeCorrection);
			return;
		}
		if (displayedBeforeCorrection == PdfWorkspacePane.DocumentMode.VIEWER) {

			// The standalone viewer is not a managed capture session and was not closed.
			pdfWorkspace.showPage(PdfWorkspacePane.DocumentMode.VIEWER, pageBeforeCorrection);
		}
	}

	private void resumeSearchAfterEdit(Stage primaryStage, QuestionSearchDialog dialog, long questionId,
			CurriculumRepository curriculumRepository, LegacyQuestionMetadataService metadataService) {
		dialog.refreshAfterEdit(questionId);
		showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
	}

	private void reviewCurriculumMappings(Stage primaryStage, ApplicationConfig config) {
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			CurriculumRepository repository = new SqliteCurriculumRepository(database);
			CurriculumMappingRepository mappingRepository = new SqliteCurriculumMappingRepository(database);
			CurriculumMappingSuggester descriptorSuggester = new TfIdfCurriculumMappingSuggester(repository);
			CurriculumMappingSuggester subtopicSuggester = new ConfirmedDescriptorSubtopicMappingSuggester(repository,
					mappingRepository);
			CurriculumMappingReviewRepository reviewRepository = new SqliteCurriculumMappingReviewRepository(database);
			CurriculumMappingCoverageService coverageService = new CurriculumMappingCoverageService(repository,
					mappingRepository, reviewRepository);
			SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
			SubtopicMappingEvidenceService subtopicEvidenceService = new SubtopicMappingEvidenceService(repository,
					reviewRepository);
			CurriculumMappingReviewDialog dialog = new CurriculumMappingReviewDialog(primaryStage, repository,
					descriptorSuggester, subtopicSuggester, subtopicEvidenceService, reviewRepository,
					mappingRepository, coverageService, reviewWriter);
			dialog.showAndWait();
		} catch (IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Mapping", "Could not load curriculum mappings.",
					e.getMessage());
		}
	}

	private Path revisionExportDestination(Path parent, Subject subject) {
		if (parent == null) {
			throw new NullPointerException("parent");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		Path normalizedParent = parent.toAbsolutePath().normalize();
		String subjectName = subject.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+", "").replaceAll("-+$", "");
		if (subjectName.isBlank()) {
			subjectName = "subject-" + subject.getId();
		}
		String baseName = subjectName + "-revision";
		Path destination = normalizedParent.resolve(baseName);
		int suffix = FIRST_DUPLICATE_SUFFIX;
		while (Files.exists(destination)) {
			destination = normalizedParent.resolve(baseName + "-" + suffix);
			suffix++;
		}
		return destination;
	}

	private Path scormExportDestination(Path parent, Subject subject) {
		if (parent == null) {
			throw new NullPointerException("parent");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		Path normalizedParent = parent.toAbsolutePath().normalize();
		String subjectName = subject.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+", "").replaceAll("-+$", "");
		if (subjectName.isBlank()) {
			subjectName = "subject-" + subject.getId();
		}
		String baseName = subjectName + "-revision-scorm";
		Path destination = normalizedParent.resolve(baseName + ".zip");
		int suffix = FIRST_DUPLICATE_SUFFIX;
		while (Files.exists(destination)) {
			destination = normalizedParent.resolve(baseName + "-" + suffix + ".zip");
			suffix++;
		}
		return destination;
	}

	private void setViewerMode(boolean viewerMode) {
		if (viewerMode) {
			if (workspaceSplitPane.getItems().contains(previewScrollPane)) {
				if (!workspaceSplitPane.getDividers().isEmpty()) {
					captureDividerPosition = workspaceSplitPane.getDividers().getFirst().getPosition();
				}
				workspaceSplitPane.getItems().remove(previewScrollPane);
			}
			return;
		}
		if (workspaceSplitPane.getItems().contains(previewScrollPane)) {
			return;
		}
		workspaceSplitPane.getItems().add(0, previewScrollPane);
		Platform.runLater(() -> workspaceSplitPane.setDividerPositions(captureDividerPosition));
	}

	private void showAbout() {
		String version = applicationVersion();
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("About Exam Question Bank");
		alert.setHeaderText("Exam Question Bank");
		alert.setContentText("""
				An application for importing, classifying, capturing and managing examination questions.

				Version: %s
				""".formatted(version).strip());
		alert.showAndWait();
	}

	private void showAlert(Alert.AlertType type, String title, String header, String message) {
		Alert alert = new Alert(type);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
	}

	private BackupFailureDecision showAutomaticBackupFailure(Stage primaryStage, Throwable failure) {
		ButtonType retryButton = new ButtonType("Retry", ButtonBar.ButtonData.OK_DONE);
		ButtonType exitWithoutBackupButton = new ButtonType("Exit Without Backup", ButtonBar.ButtonData.NO);
		ButtonType cancelExitButton = new ButtonType("Cancel Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(primaryStage);
		alert.setTitle("Automatic Backup Failed");
		alert.setHeaderText("The automatic database backup could not be completed.");
		alert.setContentText(failureMessage(failure));
		alert.getButtonTypes().setAll(retryButton, exitWithoutBackupButton, cancelExitButton);
		Optional<ButtonType> result = alert.showAndWait();
		if (result.isEmpty() || result.get() == cancelExitButton) {
			return BackupFailureDecision.CANCEL_EXIT;
		}
		if (result.get() == retryButton) {
			return BackupFailureDecision.RETRY;
		}
		return BackupFailureDecision.EXIT_WITHOUT_BACKUP;
	}

	private void showCaptureWorkspaceMode() {

		// Reattach the original live Capture controls rather than rebuilding them.
		workspaceModeHost.getChildren().setAll(captureWorkspaceModePane);

		// Any later Exam/Assets changes must be reflected immediately when the user
		// returns to Capture mode.
		refreshActiveExamContext();
	}

	private void showCurriculumAuthoring(Stage primaryStage, ApplicationConfig config) {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteCurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		CurriculumDraftLoader draftLoader = new CurriculumDraftLoader(
				new SqliteCurriculumAuthoringRepository(database));
		CurriculumAuthoringOpenService openService = new CurriculumAuthoringOpenService(curriculumRepository,
				draftLoader);
		CurriculumAuthoringCreationService creationService = new CurriculumAuthoringCreationService(
				new SqliteCurriculumImporter(database, curriculumWriter), draftLoader);
		List<SyllabusVersion> versions = openService.availableVersions();
		ButtonType openExistingButton = new ButtonType("Open Existing", ButtonBar.ButtonData.OK_DONE);
		ButtonType newCurriculumButton = new ButtonType("New Curriculum", ButtonBar.ButtonData.OTHER);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert chooser = new Alert(Alert.AlertType.CONFIRMATION);
		chooser.initOwner(primaryStage);
		chooser.setTitle("Curriculum Authoring");
		chooser.setHeaderText("Open or create a curriculum");
		if (versions.isEmpty()) {
			chooser.setContentText("No existing curricula are available.");
			chooser.getButtonTypes().setAll(newCurriculumButton, cancelButton);
		} else {
			chooser.setContentText("Choose whether to continue an existing curriculum or create a new one.");
			chooser.getButtonTypes().setAll(openExistingButton, newCurriculumButton, cancelButton);
		}
		ButtonType action = chooser.showAndWait().orElse(cancelButton);
		if (action == cancelButton) {
			return;
		}
		CurriculumAuthoringSession session;
		if (action == newCurriculumButton) {
			session = createNewCurriculum(primaryStage, curriculumRepository, creationService);
		} else {
			session = chooseExistingCurriculum(primaryStage, versions, openService);
		}
		if (session == null) {
			return;
		}
		openCurriculumAuthoringWindow(primaryStage, config, database, session);
	}

	private void showExamAssetsMode() {
		if (!allowExamAssetsTransition()) {
			return;
		}
		if (workingSubject == null) {
			showAlert(Alert.AlertType.WARNING, "Exam / Assets", "No Working Subject is selected.",
					"Select a Working Subject before managing Exam assets.");
			return;
		}
		try {

			// Re-read the current Subject's Exam hierarchy every time this workspace is
			// entered so the screen never relies on stale modal-setup state.
			examAssetsPane.refresh(workingSubject);
			workspaceModeHost.getChildren().setAll(examAssetsPane);
		} catch (SQLException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "Exam assets could not be loaded.",
					exception.getMessage());
		}
	}

	private void showHelpContents() {
		Window owner = primaryWindow();

		// Help is application documentation rather than mutable workflow state, so
		// each invocation opens a fresh viewer owned by the current application window.
		HelpDialog dialog = new HelpDialog(owner);
		dialog.showAndWait();
	}

	private void showLegacyQuestionImportResult(LegacyQuestionImportResult importResult) {
		String message = """
				Questions imported: %d
				Questions already present: %d
				Answers imported: %d
				""".formatted(importResult.insertedQuestions(), importResult.existingQuestions(),
				importResult.insertedAnswers());

		// Exam and booklet structure is now managed exclusively by Exam/Assets and is
		// therefore deliberately absent from the legacy-import result count.
		showAlert(Alert.AlertType.INFORMATION, "Legacy Question Import", "Legacy Question metadata imported.", message);
	}

	private void showOptions(Stage primaryStage, ApplicationConfig config) {
		OptionsDialog dialog = new OptionsDialog(primaryStage, config.dataRoot());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		try {
			Path dataRoot = dialog.getDataRoot();
			if (dataRoot.equals(config.dataRoot())) {
				return;
			}

			// Options always updates the same user-writable configuration used at
			// application startup, never a file beside the installed executable.
			ApplicationConfig.saveDataRoot(ApplicationPaths.propertiesFile(), dataRoot);
			showAlert(Alert.AlertType.INFORMATION, "Options", "Options saved.",
					"The new data location will be used after the application is restarted.");
		} catch (IllegalArgumentException e) {
			showAlert(Alert.AlertType.ERROR, "Options", "The data location is invalid.", e.getMessage());
		} catch (IOException e) {
			showAlert(Alert.AlertType.ERROR, "Options", "Could not save the application options.", e.getMessage());
		}
	}

	private void showQuestionCorpusAudit(Stage primaryStage, ApplicationConfig config) {
		if (blockWhileCaptureSaveInProgress(primaryStage, "opening the corpus audit")) {
			return;
		}
		if (workingSubject == null) {

			// Corpus Audit uses the same authoritative application Subject boundary as
			// capture and Search Questions.
			showAlert(Alert.AlertType.WARNING, "Corpus Audit", "No Working Subject is selected.",
					"Select a Working Subject before opening Corpus Audit.");
			return;
		}
		QuestionCorpusAuditDialog dialog = new QuestionCorpusAuditDialog(primaryStage, workingSubject,
				questionRepository.findAll());
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(
				new SqliteDatabase(config.databasePath()));
		dialog.setBulkResponseTypeHandler((questions, responseType) -> {
			try {
				metadataService.resolveUnknownResponseTypes(questions, responseType);
				questionCapturePane.refreshImportedQuestions();
				answerCapturePane.refreshQuestions();

				// The dialog retains its original Working Subject and re-scopes every
				// refreshed repository snapshot internally.
				dialog.refreshQuestions(questionRepository.findAll(), -1L);
			} catch (IllegalArgumentException | IllegalStateException exception) {
				showAlert(Alert.AlertType.ERROR, "Resolve Response Types",
						"The selected response types could not be saved.", exception.getMessage());
				dialog.refreshQuestions(questionRepository.findAll(), questions.getFirst().getId());
			}
		});
		showQuestionCorpusAuditDialog(primaryStage, config, dialog);
	}

	private void showQuestionCorpusAuditDialog(Stage primaryStage, ApplicationConfig config,
			QuestionCorpusAuditDialog dialog) {
		Optional<QuestionCorpusAuditDialog.ResolutionRequest> result = dialog.showAndWait();
		if (result.isEmpty()) {
			return;
		}
		QuestionCorpusAuditDialog.ResolutionRequest request = result.get();
		Question question = request.question();
		switch (request.target()) {
		case METADATA -> {
			Runnable resumeAudit = () -> {
				dialog.refreshQuestions(questionRepository.findAll(), question.getId());
				Platform.runLater(() -> showQuestionCorpusAuditDialog(primaryStage, config, dialog));
			};
			editCorpusQuestionMetadata(primaryStage, config, question, resumeAudit);
		}
		case QUESTION -> questionCapturePane.captureImportedQuestion(question);
		case ANSWER -> {
			if (question.hasAnswer()) {

				// An existing Answer edit temporarily opens its assigned Answer PDF.
				// Save and Cancel must both release that document.
				answerCapturePane.editAnswer(question, pdfWorkspace::closeAnswerPdf);
			} else {
				answerCapturePane.captureAnswer(question);
			}
		}
		}
	}

	private void showQuestionSearch(Stage primaryStage, ApplicationConfig config) {
		if (workingSubject == null) {

			// Search is scoped to the authoritative application Working Subject.
			// Prevent the menu action from constructing a subject-less Search dialog.
			showAlert(Alert.AlertType.WARNING, "Search Questions", "No Working Subject is selected.",
					"Select a Working Subject before searching Questions.");
			return;
		}
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(questionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(config.pdfDataRoot()),
				questionExtractor);
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(database);
		SqliteQuestionOutputApplicabilityRepository outputApplicabilityRepository = new SqliteQuestionOutputApplicabilityRepository(
				database);

		// Search inherits the authoritative workspace Working Subject. It does not
		// establish a separate application-level Subject selection.
		QuestionSearchDialog dialog = new QuestionSearchDialog(primaryStage, workingSubject, curriculumRepository,
				retrievalService, questionRepository::findAll, previewService, outputApplicabilityRepository,
				questionRepository::updateClassification);
		showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
	}

	private void showQuestionSearchDialog(Stage primaryStage, QuestionSearchDialog dialog,
			CurriculumRepository curriculumRepository, LegacyQuestionMetadataService metadataService) {
		Optional<QuestionSearchDialog.EditRequest> result = dialog.showAndWait();
		if (result.isEmpty()) {
			dialog.dispose();
			questionCapturePane.clearSaveStatus();
			return;
		}
		QuestionSearchDialog.EditRequest request = result.get();
		Question question = request.question();
		if (request.target() == QuestionSearchDialog.EditTarget.METADATA) {
			editQuestionMetadata(primaryStage, dialog, question, curriculumRepository, metadataService);
			return;
		}
		if (request.target() == QuestionSearchDialog.EditTarget.SPLIT) {
			startQuestionSplitFromSearch(primaryStage, dialog, question, curriculumRepository, metadataService);
			return;
		}
		if (request.target() == QuestionSearchDialog.EditTarget.SHARED_CONTEXT) {
			boolean correctionStarted = questionCapturePane.recaptureSharedContext(question, () -> {

				// Shared-context correction temporarily owns the Question's
				// Exam PDF. Release it after either Save or Cancel.
				pdfWorkspace.closeExamPdf();
				resumeSearchAfterEdit(primaryStage, dialog, question.getId(), curriculumRepository, metadataService);
			});
			if (!correctionStarted) {
				showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
			}
			return;
		}
		if (request.target() == QuestionSearchDialog.EditTarget.QUESTION) {
			boolean editingStarted = questionCapturePane.editQuestion(question, () -> {

				// Search temporarily opened this Question's Exam PDF for
				// editing. Save and Cancel both release it before Search
				// becomes active again.
				pdfWorkspace.closeExamPdf();
				resumeSearchAfterEdit(primaryStage, dialog, question.getId(), curriculumRepository, metadataService);
			});
			if (!editingStarted) {
				showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
				return;
			}

			// The Question editor now owns the correct Exam document. Display the
			// persisted regions as informational grey overlays and focus the first one.
			focusStoredQuestionRegions(question);
			return;
		}

		// ANswer editing
		boolean editingStarted = answerCapturePane.editAnswer(question, () -> {

			// The Answer PDF was opened for this temporary edit. It must
			// not remain active after either Save or Cancel.
			pdfWorkspace.closeAnswerPdf();
			resumeSearchAfterEdit(primaryStage, dialog, question.getId(), curriculumRepository, metadataService);
		});
		if (!editingStarted) {
			showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
		}
	}

	private void showResourceCloseFailure(Stage primaryStage, Throwable failure) {
		showAlert(Alert.AlertType.ERROR, "Exit", "The application could not close its active resources.",
				failureMessage(failure));
	}

	private void showRetentionWarning(Stage primaryStage, Throwable failure) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.initOwner(primaryStage);
		alert.setTitle("Automatic Backup");
		alert.setHeaderText("The automatic backup was created, but old backups could not be removed.");
		alert.setContentText(failureMessage(failure));
		alert.showAndWait();
	}

	private void showRevisionExportDialog(Stage primaryStage, ApplicationConfig config) {
		if (revisionExportRunning) {
			return;
		}
		RevisionExportService eligibilityService = createRevisionExportService(config);
		RevisionExportDialog dialog = new RevisionExportDialog(primaryStage, curriculumSelectionModel.getSubjects(),
				curriculumSelectionModel.getSubject(), eligibilityService::findExportableUnits,
				eligibilityService::isDescriptorGroupingAvailable);
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		Subject subject = dialog.getSelectedSubject();
		Path destinationParent = dialog.getDestinationParent();
		RevisionGroupingMode groupingMode = dialog.getGroupingMode();
		Set<Long> selectedUnitIds = dialog.getSelectedUnitIds();
		if (subject == null || destinationParent == null || groupingMode == null || selectedUnitIds.isEmpty()) {
			return;
		}
		Path destination = revisionExportDestination(destinationParent, subject);
		startRevisionExport(primaryStage, config, subject, destination, groupingMode, selectedUnitIds);
	}

	private void showRevisionExportSuccess(RevisionExportResult result) {
		String message = """
				Export location:
				%s

				Applicable questions: %d
				Exportable questions: %d
				Awaiting question capture: %d
				Questions without answers: %d
				Shared context review flags: %d
				""".formatted(result.getDestination(), result.getStatistics().getUniqueApplicableQuestions(),
				result.getStatistics().getRenderableQuestions(),
				result.getStatistics().getMissingQuestionRegionQuestions(),
				result.getStatistics().getQuestionsWithoutAnswers(),
				result.getStatistics().getSharedContextReviewQuestions());
		showAlert(Alert.AlertType.INFORMATION, "Export Revision HTML", "Revision website exported successfully.",
				message);
	}

	private void showScormExportDialog(Stage primaryStage, ApplicationConfig config) {
		if (scormExportRunning) {
			return;
		}
		ScormExportService eligibilityService = createScormExportService(config);
		ScormExportDialog dialog = new ScormExportDialog(primaryStage, curriculumSelectionModel.getSubjects(),
				curriculumSelectionModel.getSubject(), eligibilityService::findExportableUnits,
				eligibilityService::isDescriptorGroupingAvailable);
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		Subject subject = dialog.getSelectedSubject();
		Path destinationParent = dialog.getDestinationParent();
		RevisionGroupingMode groupingMode = dialog.getGroupingMode();
		Set<Long> selectedUnitIds = dialog.getSelectedUnitIds();
		if (subject == null || destinationParent == null || groupingMode == null || selectedUnitIds.isEmpty()) {
			return;
		}
		Path destination = scormExportDestination(destinationParent, subject);
		startScormExport(primaryStage, config, subject, destination, groupingMode, selectedUnitIds);
	}

	private void showScormExportSuccess(ScormExportResult result) {
		String message = """
				SCORM ZIP:
				%s

				Applicable questions: %d
				Exportable questions: %d
				Awaiting question capture: %d
				Questions without answers: %d
				Shared context review flags: %d
				""".formatted(result.getDestination(), result.getStatistics().getUniqueApplicableQuestions(),
				result.getStatistics().getRenderableQuestions(),
				result.getStatistics().getMissingQuestionRegionQuestions(),
				result.getStatistics().getQuestionsWithoutAnswers(),
				result.getStatistics().getSharedContextReviewQuestions());
		showAlert(Alert.AlertType.INFORMATION, "Export Revision SCORM", "SCORM package exported successfully.",
				message);
	}

	private void showStage(Stage primaryStage, BorderPane root) {
		Scene scene = new Scene(root, SCENE_WIDTH, SCENE_HEIGHT);
		primaryStage.setTitle("Exam Question Bank");
		primaryStage.setScene(scene);
		primaryStage.show();
	}

	private void showStartupError(String title, String message) {
		showAlert(Alert.AlertType.ERROR, title, "The application could not start.", message);
	}

	private void showVersionInformation(ApplicationConfig config) {
		String applicationVersion = applicationVersion();
		String javaVersion = System.getProperty("java.version", "Unknown");
		String javaFxVersion = System.getProperty("javafx.runtime.version", "Unknown");
		String operatingSystem = System.getProperty("os.name", "Unknown") + " " + System.getProperty("os.version", "");
		String sqliteVersion = "Unknown";
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			sqliteVersion = database.sqliteVersion();
		} catch (SQLException e) {
			sqliteVersion = "Unavailable";
		}
		String information = """
				Application: Exam Question Bank
				Version: %s
				Java: %s
				JavaFX: %s
				Operating system: %s
				SQLite: %s
				Database schema: %d
				""".formatted(applicationVersion, javaVersion, javaFxVersion, operatingSystem.trim(), sqliteVersion,
				SqliteDatabase.latestSchemaVersion());
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("Version Information");
		alert.setHeaderText("Exam Question Bank");
		alert.setContentText(information);
		alert.showAndWait();
	}

	private void startApplication(Stage primaryStage, ApplicationConfig config) throws SQLException {
		curriculumSelectionModel = new CurriculumSelectionModelFactory().create(config);
		PdfFilePicker answerPdfPicker = new PdfFilePicker(config.pdfDataRoot());
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		configureShutdown(config);
		initialiseCaptureWorkflow(primaryStage, config, database, answerPdfPicker);
		configurePdfWorkspace();
		configurePrimaryStage(primaryStage, config);
	}

	private void startLegacyQuestionCaptureRefresh(Subject subject, LegacyQuestionImportResult importResult) {
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (importResult == null) {
			throw new NullPointerException("importResult");
		}
		Task<List<Question>> task = new Task<>() {

			@Override
			protected List<Question> call() {

				// Legacy import has already committed. Load one immutable corpus snapshot
				// away from JavaFX and publish that same snapshot to both capture panes.
				return List.copyOf(workingSubjectQuestionSnapshotLoader.get());
			}
		};
		task.setOnSucceeded(_ -> {
			if (!Objects.equals(workingSubject, subject)) {

				// A later Working Subject owns the application now. Imported persistence
				// remains valid, but its old Subject must not overwrite current UI state.
				return;
			}
			List<Question> questions = task.getValue();

			// Both capture panes consume exactly the same post-import corpus read.
			questionCapturePane.setWorkingSubject(subject, questions);
			answerCapturePane.setWorkingSubject(subject, questions);

			// setWorkingSubject(...) already recalculates imported/incomplete Question
			// availability, so do not call showLegacyCaptureControls(), which would
			// perform another repository-backed refresh.
			showLegacyQuestionImportResult(importResult);
		});
		task.setOnFailed(_ -> {
			if (!Objects.equals(workingSubject, subject)) {

				// A refresh failure for application context the user has already left is
				// stale and must not interrupt the current workflow.
				return;
			}
			showAlert(Alert.AlertType.WARNING, "Legacy Question Import",
					"Question metadata was imported, but capture state could not be refreshed.",
					"The imported metadata is stored safely. " + failureMessage(task.getException()));
		});
		Thread thread = new Thread(task, "legacy-question-capture-refresh");
		thread.setDaemon(true);
		thread.start();
	}

	private void startQuestionSplitFromSearch(Stage primaryStage, QuestionSearchDialog searchDialog, Question question,
			CurriculumRepository curriculumRepository, LegacyQuestionMetadataService metadataService) {
		SharedQuestionContext existingSharedContext;
		try {
			existingSharedContext = findExistingSplitSharedContext(question);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			showAlert(Alert.AlertType.ERROR, "Split Question",
					"The existing multipart relationships must be corrected before this Question can be split.",
					exception.getMessage());
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
			return;
		}
		LegacyQuestionSplitDialog splitDialog = new LegacyQuestionSplitDialog(primaryStage, question,
				curriculumRepository, existingSharedContext);
		Optional<LegacyQuestionSplitDialog.Result> splitDefinition = splitDialog.showAndWait();
		if (splitDefinition.isEmpty()) {

			// Cancelling the definition phase changes nothing and returns directly to
			// the existing Search dialog.
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
			return;
		}
		boolean captureStarted = questionCapturePane.beginLegacyQuestionSplit(question, splitDefinition.get(), () -> {

			// Split correction uses the original Question's Exam PDF only
			// for the lifetime of the correction workflow.
			pdfWorkspace.closeExamPdf();
			resumeSearchAfterEdit(primaryStage, searchDialog, question.getId(), curriculumRepository, metadataService);
		});
		if (!captureStarted) {
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
		}
	}

	private void startRevisionExport(Stage primaryStage, ApplicationConfig config, Subject subject, Path destination,
			RevisionGroupingMode groupingMode, Set<Long> selectedUnitIds) {
		if (revisionExportRunning) {
			return;
		}
		revisionExportRunning = true;
		if (revisionExportMenuItem != null) {
			revisionExportMenuItem.setDisable(true);
		}
		RevisionExportService exportService = createRevisionExportService(config);
		RevisionExportRequest request = new RevisionExportRequest(subject, destination, groupingMode, selectedUnitIds);
		RevisionExportTask task = new RevisionExportTask(exportService, request);
		Alert progressAlert = createExportProgressAlert(primaryStage, task, "Export Revision HTML",
				"Creating revision website...", "Starting export...");
		task.setOnSucceeded(_ -> completeRevisionExport(task, progressAlert));
		task.setOnFailed(_ -> failRevisionExport(task, progressAlert));
		progressAlert.show();
		Thread thread = new Thread(task, "revision-html-export");
		thread.setDaemon(true);
		thread.start();
	}

	private void startScormExport(Stage primaryStage, ApplicationConfig config, Subject subject, Path destination,
			RevisionGroupingMode groupingMode, Set<Long> selectedUnitIds) {
		if (scormExportRunning) {
			return;
		}
		scormExportRunning = true;
		if (scormExportMenuItem != null) {
			scormExportMenuItem.setDisable(true);
		}
		ScormExportService exportService = createScormExportService(config);
		ScormExportRequest request = new ScormExportRequest(subject, destination, groupingMode, selectedUnitIds);
		ScormExportTask task = new ScormExportTask(exportService, request);
		Alert progressAlert = createExportProgressAlert(primaryStage, task, "Export Revision SCORM",
				"Creating SCORM package...", "Starting SCORM export...");
		task.setOnSucceeded(_ -> completeScormExport(task, progressAlert));
		task.setOnFailed(_ -> failScormExport(task, progressAlert));
		progressAlert.show();
		Thread thread = new Thread(task, "revision-scorm-export");
		thread.setDaemon(true);
		thread.start();
	}

	private void startWorkingSubjectCaptureRefresh(Subject subject, long generation) {

		// Remove curriculum state belonging to the previous Subject immediately while
		// keeping all persistence reads off the JavaFX thread.
		if (curriculumSelectorPane != null) {
			curriculumSelectorPane.beginSubjectRefresh(subject);
		}

		// Clear previous Subject queues immediately; persistence remains on the worker.
		if (questionCapturePane != null) {
			questionCapturePane.setWorkingSubject(subject, List.of());
		}
		if (answerCapturePane != null) {
			answerCapturePane.setWorkingSubject(subject, List.of());
		}
		if (subject == null) {

			// Clearing Working Subject requires no replacement persistence snapshot.
			return;
		}
		Task<WorkingSubjectCaptureSnapshot> task = new Task<>() {

			@Override
			protected WorkingSubjectCaptureSnapshot call() {

				// Load curriculum first so successful publication can establish
				// classification choices before either capture pane exposes Questions.
				CurriculumSelectionModel.SubjectSnapshot curriculumSnapshot = workingSubjectCurriculumSnapshotLoader
						.apply(subject);

				// One corpus read supplies both capture panes for this Subject transition.
				List<Question> questions = List.copyOf(workingSubjectQuestionSnapshotLoader.get());
				return new WorkingSubjectCaptureSnapshot(curriculumSnapshot, questions);
			}
		};
		task.setOnSucceeded(_ -> completeWorkingSubjectCaptureRefresh(subject, generation, task.getValue()));
		task.setOnFailed(_ -> failWorkingSubjectCaptureRefresh(subject, generation, task.getException()));
		Thread thread = new Thread(task, "working-subject-capture-refresh-" + generation);
		thread.setDaemon(true);
		thread.start();
	}

	private void startWorkingSubjectExamAssetsRefresh(Subject subject, long generation) {
		if (examAssetsPane == null || workspaceModeHost == null
				|| !workspaceModeHost.getChildren().contains(examAssetsPane)) {

			// Hidden Exam/Assets needs no immediate Subject snapshot. Its ordinary entry
			// path will load the then-current authoritative Subject.
			return;
		}

		// Remove the previous Subject's Exams immediately on the FX thread before the
		// replacement hierarchy begins loading.
		examAssetsPane.beginSubjectRefresh(subject);
		if (subject == null) {

			// Clearing Subject is complete once stale presentation has been removed.
			return;
		}
		Task<ExamAssetsPane.SubjectSnapshot> task = new Task<>() {

			@Override
			protected ExamAssetsPane.SubjectSnapshot call() {

				// All Exam, booklet, AnswerFile and assignment reads occur away from
				// the JavaFX application thread.
				return workingSubjectExamAssetsSnapshotLoader.apply(subject);
			}
		};
		task.setOnSucceeded(_ -> {
			if (!isCurrentWorkingSubjectRefresh(subject, generation)) {

				// A later Subject selection owns the workspace now.
				return;
			}
			examAssetsPane.applySubjectSnapshot(task.getValue());
		});
		task.setOnFailed(_ -> {
			if (!isCurrentWorkingSubjectRefresh(subject, generation)) {

				// Failure from stale work must not disturb the current Subject.
				return;
			}
			Throwable failure = task.getException();
			if (failure instanceof IllegalStateException && failure.getCause() != null) {

				// Surface the underlying SQLite failure rather than the Function wrapper.
				failure = failure.getCause();
			}
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "Exam assets could not be refreshed.",
					failureMessage(failure));
		});
		Thread thread = new Thread(task, "working-subject-exam-assets-refresh-" + generation);
		thread.setDaemon(true);
		thread.start();
	}

	private void startWorkingSubjectRefresh(Subject subject) {
		long generation = ++workingSubjectRefreshGeneration;

		// Curriculum, Question/Answer capture and visible Exam/Assets all belong to
		// this accepted application transition and share one stale-result generation.
		startWorkingSubjectCaptureRefresh(subject, generation);
		startWorkingSubjectExamAssetsRefresh(subject, generation);
	}

	private boolean transferQuestionSelectionToSharedContext() {
		if (!captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION)) {
			return false;
		}
		captureSelectionState.claim(CaptureSelectionOwner.SHARED_CONTEXT);
		return true;
	}

	private void useExamAssetsBookletForCapture(ExamBooklet booklet, ApplicationConfig config) {

		// Do not leave Exam/Assets unless the selected booklet has been successfully
		// opened and activated as the authoritative capture source.
		if (!activateBookletForCapture(booklet, config)) {
			return;
		}
		showCaptureWorkspaceMode();
	}

	private void viewAnswerFileFromExamAssets(AnswerFile answerFile, ApplicationConfig config) {
		if (answerFile == null) {
			throw new NullPointerException("answerFile");
		}

		// Answer-booklet View is also inspection only. It must not enter Answer capture
		// or replace the persisted Answer assignment.
		viewExamAssetPdf(answerFile.getSourceDocument().getRelativePath(), "Answer booklet", config);
	}

	private void viewExamAssetPdf(String relativePath, String assetDescription, ApplicationConfig config) {
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (assetDescription == null) {
			throw new NullPointerException("assetDescription");
		}
		if (config == null) {
			throw new NullPointerException("config");
		}
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Path storedPath;
		try {

			// Resolve only through the managed PDF root so Exam/Assets never opens an
			// arbitrary external path recorded outside application storage.
			storedPath = pdfStore.resolve(relativePath);
		} catch (IllegalArgumentException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets",
					"The stored " + assetDescription + " PDF path is invalid.", exception.getMessage());
			return;
		}
		if (!Files.isRegularFile(storedPath)) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "The stored " + assetDescription + " PDF is unavailable.",
					storedPath.toString());
			return;
		}
		try {

			// VIEWER mode disables region capture but, unlike the old modal inspection
			// workflow, the Exam/Assets pane remains visible beside the shared PDF pane.
			pdfWorkspace.openViewerPdf(storedPath);
		} catch (RuntimeException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "The " + assetDescription + " could not be opened.",
					failureMessage(exception));
		}
	}

	private boolean viewPendingQuestionBookletSourceFromExamAssets(Path sourcePath) {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Path normalizedSource = sourcePath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalizedSource)) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "The selected Question booklet PDF is unavailable.",
					normalizedSource.toString());
			return false;
		}
		try {

			// A pending source is deliberately allowed to be outside managed storage:
			// the user has just selected it and it has not yet been imported. VIEWER mode
			// keeps inspection read-only and does not alter the capture booklet.
			pdfWorkspace.openViewerPdf(normalizedSource);
			return true;
		} catch (RuntimeException exception) {
			showAlert(Alert.AlertType.ERROR, "Exam / Assets", "The selected Question booklet could not be opened.",
					failureMessage(exception));
			return false;
		}
	}

	private void viewQuestionBookletFromExamAssets(ExamBooklet booklet, ApplicationConfig config) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}

		// Question-booklet View is inspection only. It must not activate this booklet
		// for capture or change ExamMetadataPane capture state.
		viewExamAssetPdf(booklet.getSourceDocument().getRelativePath(), "Question booklet", config);
	}

	private record PendingLegacyQuestionImport(Subject subject, SyllabusVersion syllabusVersion, Path workbookPath) {

		private PendingLegacyQuestionImport {
			if (subject == null) {
				throw new NullPointerException("subject");
			}
			if (syllabusVersion == null) {
				throw new NullPointerException("syllabusVersion");
			}
			if (workbookPath == null) {
				throw new NullPointerException("workbookPath");
			}

			// The historical syllabus must belong to the same authoritative Subject as
			// the pending workbook import.
			if (!subject.equals(syllabusVersion.getSubject())) {
				throw new IllegalArgumentException("Legacy import syllabus does not belong to Working Subject");
			}
		}
	}

	private enum BackupFailureDecision {
		RETRY, EXIT_WITHOUT_BACKUP, CANCEL_EXIT
	}

	private record WorkingSubjectCaptureSnapshot(CurriculumSelectionModel.SubjectSnapshot curriculumSnapshot,
			List<Question> questions) {

		private WorkingSubjectCaptureSnapshot {
			if (curriculumSnapshot == null) {
				throw new NullPointerException("curriculumSnapshot");
			}
			if (questions == null) {
				throw new NullPointerException("questions");
			}

			// Freeze the shared corpus before the worker publishes it to both capture
			// panes.
			questions = List.copyOf(questions);
		}
	}
}
