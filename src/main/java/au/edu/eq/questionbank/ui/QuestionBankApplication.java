package au.edu.eq.questionbank.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.curriculum.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletImportRequest;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
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
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionMetadataService;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionMetadataUpdateResult;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionCaptureService;
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
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlanner;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModelFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
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
import javafx.scene.layout.BorderPane;
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

	private static final double SECTION_SPACING = 10.0;
	private static final double PREVIEW_PANE_INITIAL_WIDTH = 525.0;
	private static final double PREVIEW_PANE_MIN_WIDTH = 400.0;
	private static final double SCENE_WIDTH = 1400.0;
	private static final double SCENE_HEIGHT = 840.0;
	private static final double INITIAL_WORKSPACE_DIVIDER_POSITION = PREVIEW_PANE_INITIAL_WIDTH / SCENE_WIDTH;
	private static final Insets PREVIEW_PANE_PADDING = new Insets(10);
	private static final Path PROPERTIES_FILE = Path.of("questionbank.properties");
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
	private ExamImportDialog examImportDialog;
	private Runnable applicationExitAction = Platform::exit;
	private ShutdownCoordinator shutdownCoordinator;
	private boolean resourcesClosedForRestore;
	private MenuItem revisionExportMenuItem;
	private boolean revisionExportRunning;
	private MenuItem scormExportMenuItem;
	private boolean scormExportRunning;
	private SplitPane workspaceSplitPane;

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
		ApplicationConfig config;
		try {
			config = ApplicationConfig.load(PROPERTIES_FILE);
		} catch (ConfigurationException e) {
			showStartupError("Configuration Error", e.getMessage());
			return;
		} catch (IOException e) {
			showStartupError("Configuration Error", "Could not read questionbank.properties:\n" + e.getMessage());
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

	private boolean allowExamImportConfirmation() {
		if (captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION)) {

			// Do not allow the active exam to change while a Question selection is pending.
			showAlert(Alert.AlertType.WARNING, "Open Exam for Capture", "Question selection pending",
					"Add or clear the current question selection before opening another exam for capture.");
			return false;
		}
		return confirmDiscardAcceptedQuestionRegions();
	}

	private boolean allowPdfPageNavigation() {
		if (!captureSelectionState.hasPendingSelection()) {
			return true;
		}
		showAlert(Alert.AlertType.WARNING, "Selection pending", "Selection pending",
				"Add or clear the current selection before changing PDF pages.");
		return false;
	}

	private String applicationVersion() {
		String applicationVersion = getClass().getPackage().getImplementationVersion();
		if (applicationVersion == null || applicationVersion.isBlank()) {
			return "Development build";
		}
		return applicationVersion;
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

	/**
	 * Transfers ownership of a newly completed PDF selection and removes stale
	 * local pending-selection state from the previous workflow.
	 *
	 * @param newOwner workflow receiving the newly completed selection
	 */
	private void claimCaptureSelection(CaptureSelectionOwner newOwner) {
		CaptureSelectionOwner previousOwner = captureSelectionState.getOwner();
		/*
		 * Record the new owner first. Any local cleanup performed below must not clear
		 * the newly completed rectangle from the shared PDF workspace.
		 */
		captureSelectionState.claim(newOwner);
		if (previousOwner == null || previousOwner == newOwner) {
			return;
		}
		if (previousOwner == CaptureSelectionOwner.ANSWER) {

			// A new Question-side selection supersedes the stale Answer selection.
			answerCapturePane.discardCurrentSelectionForOwnershipLoss();
			return;
		}
		/*
		 * QUESTION and SHARED_CONTEXT both live inside QuestionCapturePane, which
		 * clears the appropriate stale local state without touching the PDF rectangle.
		 */
		questionCapturePane.discardCurrentSelectionForOwnershipLoss();
	}

	private void clearCaptureSelection(CaptureSelectionOwner owner) {
		if (captureSelectionState.clear(owner)) {
			pdfWorkspace.clearSelection();
		}
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

	private void configurePdfWorkspace() {
		pdfWorkspace.setSelectionAvailable(this::isRegionSelectionAvailable);
		pdfWorkspace.setSelectionHandler(this::handleRegionSelection);
		pdfWorkspace.setPageNavigationAllowed(this::allowPdfPageNavigation);
		pdfWorkspace.setSelectionModeChangedHandler(this::handleSelectionModeChanged);
	}

	private void configurePrimaryStage(Stage primaryStage, ApplicationConfig config) {
		primaryStage.setOnCloseRequest(event -> handleCloseRequest(event, primaryStage));
		showStage(primaryStage, createRootLayout(primaryStage, config));
		examImportDialog = new ExamImportDialog(primaryStage, examMetadataPane);
	}

	private void configureShutdown(ApplicationConfig config) {
		DefaultBackupService automaticBackupService = new DefaultBackupService(config, applicationVersion());
		shutdownCoordinator = new ShutdownCoordinator(automaticBackupService, BackupRequest.automaticDatabase(config),
				new AutomaticBackupRetention(), pdfWorkspace);
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

	private Menu createCurriculumMenu(Stage primaryStage, ApplicationConfig config) {
		Menu curriculumMenu = createMenu("_Curriculum");
		MenuItem authorItem = createMenuItem("_Author / Edit...", () -> showCurriculumAuthoring(primaryStage, config));
		/*
		 * Retain the existing id so any UI automation referring to this menu action
		 * remains compatible.
		 */
		authorItem.setId("author-curriculum-pdf");
		curriculumMenu.getItems().addAll(createMenuItem("_Import...", () -> importCurriculum(primaryStage, config)),
				authorItem, new SeparatorMenuItem(),
				createMenuItem("_Review Mappings...", () -> reviewCurriculumMappings(primaryStage, config)));
		return curriculumMenu;
	}

	private CurriculumSelectorPane createCurriculumSelectorPane() {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);
		selectorPane.selectedSubjectProperty().addListener((_, _, newSubject) -> handleSubjectChanged(newSubject));
		return selectorPane;
	}

	private Menu createExamMenu(Stage primaryStage, ApplicationConfig config) {
		Menu examMenu = createMenu("_Exam");

		// Describe the user's task rather than the current persistence implementation.
		MenuItem openForCaptureItem = createMenuItem("_Open Exam for Capture...", this::showExamImport);
		openForCaptureItem.setId("open-exam-for-capture");
		examMenu.getItems().addAll(openForCaptureItem, createMenuItem("Import _Legacy Question Metadata...",
				() -> importLegacyQuestionMetadata(primaryStage, config)));
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
		progressBar.setPrefWidth(360);
		progressBar.progressProperty().bind(task.progressProperty());
		VBox progressContent = new VBox(10, progressLabel, progressBar);
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
		helpMenu.getItems().addAll(createMenuItem("_About...", this::showAbout),
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

	private void createMissingLegacyBooklets(ApplicationConfig config, Subject subject,
			List<LegacyBookletImportRequest> requests) throws IOException, SQLException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		Set<Long> importedAnswerFileExamIds = new HashSet<>();
		for (LegacyBookletImportRequest request : requests) {
			LegacyBookletRequirement requirement = request.requirement();
			Path storedPath = pdfStore.importExamPdf(request.pdfPath(), subject.getName(), requirement.providerName(),
					requirement.year());
			String relativePath = config.pdfDataRoot().relativize(storedPath).toString();
			ExamBooklet booklet = examImporter.importExam(subject, requirement.providerName(), requirement.year(),
					request.assessmentName(), requirement.bookletName(), relativePath);
			if (request.answerPdfPath() == null) {
				continue;
			}
			if (!importedAnswerFileExamIds.add(booklet.getExam().getId())) {
				continue;
			}
			Path storedAnswerPath = pdfStore.importExamPdf(request.answerPdfPath(), subject.getName(),
					requirement.providerName(), requirement.year());
			String answerRelativePath = config.pdfDataRoot().relativize(storedAnswerPath).toString();
			answerWriter.findOrCreateAnswerFile(booklet.getExam(), "Marking guide", answerRelativePath);
		}
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
		VBox previewPane = new VBox(SECTION_SPACING, curriculumSelectorPane, questionCapturePane, answerCapturePane);
		previewPane.setPadding(PREVIEW_PANE_PADDING);
		previewPane.setMinWidth(PREVIEW_PANE_MIN_WIDTH);
		previewPane.setPrefWidth(PREVIEW_PANE_INITIAL_WIDTH);
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
		RevisionCorpusBuilder corpusBuilder = new RevisionCorpusBuilder(curriculumRepository, retrievalService);
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
		CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(database);
		LegacyQuestionMetadataDialog metadataDialog = new LegacyQuestionMetadataDialog(primaryStage, question,
				curriculumRepository);
		Optional<LegacyQuestionMetadataDialog.Result> result = metadataDialog.showAndWait();
		if (result.isEmpty()) {
			completedHandler.run();
			return;
		}
		LegacyQuestionMetadataDialog.Result replacement = result.get();
		try {
			LegacyQuestionMetadataUpdateResult updateResult = metadataService.updateMetadataWithResult(question,
					replacement.questionCode(), replacement.marks(), replacement.classification(),
					replacement.preambleCaptureRequired(), replacement.responseType());
			Question updated = updateResult.question();
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			if (updateResult
					.preambleOutcome() == LegacyQuestionMetadataUpdateResult.PreambleOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS) {
				offerQuestionRecaptureAfterPreambleConversion(primaryStage, updated, completedHandler);
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
		LegacyQuestionMetadataDialog metadataDialog = new LegacyQuestionMetadataDialog(primaryStage, question,
				curriculumRepository);
		Optional<LegacyQuestionMetadataDialog.Result> result = metadataDialog.showAndWait();
		if (result.isEmpty()) {
			showQuestionSearchDialog(primaryStage, searchDialog, curriculumRepository, metadataService);
			return;
		}
		LegacyQuestionMetadataDialog.Result replacement = result.get();
		try {
			LegacyQuestionMetadataUpdateResult updateResult = metadataService.updateMetadataWithResult(question,
					replacement.questionCode(), replacement.marks(), replacement.classification(),
					replacement.preambleCaptureRequired(), replacement.responseType());
			Question updated = updateResult.question();
			questionCapturePane.refreshImportedQuestions();
			answerCapturePane.refreshQuestions();
			if (updateResult
					.preambleOutcome() == LegacyQuestionMetadataUpdateResult.PreambleOutcome.CONVERTED_SHARED_CONTEXT_TO_QUESTION_REGIONS) {
				offerQuestionRecaptureAfterPreambleConversion(primaryStage, searchDialog, updated, curriculumRepository,
						metadataService);
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

	private void handleCloseRequest(javafx.stage.WindowEvent event, Stage primaryStage) {
		event.consume();
		requestApplicationExit(primaryStage);
	}

	private void handleRegionSelection(PdfWorkspacePane.RegionSelection selection) {
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.ANSWER) {
			/*
			 * The newly completed Answer rectangle becomes the application's single pending
			 * selection before AnswerCapturePane receives it.
			 */
			claimCaptureSelection(CaptureSelectionOwner.ANSWER);
			answerCapturePane.acceptSelection(selection);
			return;
		}
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.EXAM) {
			if (questionCapturePane.isCapturingSharedContext()) {
				/*
				 * Shared-context capture owns this Exam-PDF rectangle and supersedes any
				 * incompatible pending selection from another workflow.
				 */
				claimCaptureSelection(CaptureSelectionOwner.SHARED_CONTEXT);
				questionCapturePane.acceptSharedContextSelection(selection);
			} else {
				/*
				 * Ordinary Question capture owns this Exam-PDF rectangle and supersedes any
				 * incompatible pending selection from another workflow.
				 */
				claimCaptureSelection(CaptureSelectionOwner.QUESTION);
				questionCapturePane.acceptSelection(selection);
			}
		}
	}

	private void handleSelectionModeChanged() {
		CaptureSelectionOwner owner = captureSelectionState.getOwner();
		if (owner == null) {
			return;
		}
		switch (owner) {
		case QUESTION -> questionCapturePane.clearCurrentSelection();
		case SHARED_CONTEXT -> questionCapturePane.clearSharedContextCurrentSelection();
		case ANSWER -> {
			answerCapturePane.clearCurrentSelectionForPageChange();
			clearCaptureSelection(CaptureSelectionOwner.ANSWER);
		}
		}
	}

	private void handleSubjectChanged(Subject newSubject) {
		examMetadataPane.invalidateForSubjectChange(newSubject);
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

	private void importLegacyAnswerPdfs(ApplicationConfig config, Subject subject,
			List<LegacyAnswerPdfImportDialog.AnswerPdfSelection> selections) throws IOException, SQLException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		for (LegacyAnswerPdfImportDialog.AnswerPdfSelection selection : selections) {
			Exam exam = examWriter.findExamByProviderAndYear(subject, selection.providerName(), selection.year());
			if (exam == null) {
				throw new IllegalStateException("No existing exam matches " + selection.providerName() + " "
						+ selection.year() + " for " + subject.getName());
			}
			Path storedPath = pdfStore.importExamPdf(selection.pdfPath(), subject.getName(), selection.providerName(),
					selection.year());
			String relativePath = config.pdfDataRoot().relativize(storedPath).toString();
			answerWriter.findOrCreateAnswerFile(exam, "Marking guide", relativePath);
		}
	}

	private void importLegacyQuestionMetadata(Stage primaryStage, ApplicationConfig config) {
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
			LegacyQuestionImportDialog dialog = new LegacyQuestionImportDialog(primaryStage, curriculumRepository);
			Optional<ButtonType> result = dialog.showAndWait();
			if (result.isEmpty() || result.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
				return;
			}
			Subject subject = dialog.getSelectedSubject();
			SyllabusVersion syllabusVersion = dialog.getSelectedSyllabusVersion();
			LegacyQuestionMetadataImporter importer = new LegacyQuestionMetadataImporter(database);
			Optional<Integer> importedBooklets = importMissingLegacyBooklets(primaryStage, config, dialog, subject,
					syllabusVersion, importer);
			if (importedBooklets.isEmpty()) {
				return;
			}
			LegacyQuestionImportResult importResult = importer.importWorkbook(dialog.getSelectedFile(),
					subject.getName(), syllabusVersion.getName());
			answerCapturePane.refreshQuestions();
			questionCapturePane.showLegacyCaptureControls();
			showLegacyQuestionImportResult(importedBooklets.get().intValue(), importResult);
		} catch (IOException e) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "Could not read the Excel workbook.",
					e.getMessage());
		} catch (SQLException e) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "Could not save the question metadata.",
					e.getMessage());
		} catch (IllegalArgumentException | IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Legacy Question Import", "The legacy question import failed.",
					e.getMessage());
		}
	}

	private Optional<Integer> importMissingLegacyBooklets(Stage primaryStage, ApplicationConfig config,
			LegacyQuestionImportDialog dialog, Subject subject, SyllabusVersion syllabusVersion,
			LegacyQuestionMetadataImporter importer) throws IOException, SQLException {
		List<LegacyBookletRequirement> missingBooklets = importer.findMissingBooklets(dialog.getSelectedFile(),
				subject.getName(), syllabusVersion.getName());
		if (missingBooklets.isEmpty()) {
			List<LegacyBookletRequirement> requiredBooklets = importer.findRequiredBooklets(dialog.getSelectedFile(),
					subject.getName(), syllabusVersion.getName());
			if (!requiredBooklets.isEmpty()) {
				LegacyAnswerPdfImportDialog answerDialog = new LegacyAnswerPdfImportDialog(primaryStage,
						requiredBooklets);
				Optional<ButtonType> answerResult = answerDialog.showAndWait();
				if (answerResult.isEmpty() || answerResult.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
					return Optional.empty();
				}
				importLegacyAnswerPdfs(config, subject, answerDialog.getSelections());
			}
			return Optional.of(Integer.valueOf(0));
		}
		LegacyBookletImportDialog bookletDialog = new LegacyBookletImportDialog(primaryStage, missingBooklets);
		Optional<ButtonType> bookletResult = bookletDialog.showAndWait();
		if (bookletResult.isEmpty() || bookletResult.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return Optional.empty();
		}
		List<LegacyBookletImportRequest> requests = bookletDialog.getRequests();
		createMissingLegacyBooklets(config, subject, requests);
		List<LegacyBookletRequirement> stillMissing = importer.findMissingBooklets(dialog.getSelectedFile(),
				subject.getName(), syllabusVersion.getName());
		if (!stillMissing.isEmpty()) {
			throw new IllegalStateException("Required exam booklets are still missing " + "after booklet import.");
		}
		return Optional.of(Integer.valueOf(requests.size()));
	}

	private void initialiseCaptureWorkflow(Stage primaryStage, ApplicationConfig config, SqliteDatabase database,
			PdfFilePicker answerPdfPicker) {
		questionRepository = new SqliteQuestionRepository(database);
		SourceQuestionRepository sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
		SqliteQuestionCaptureService questionCaptureService = new SqliteQuestionCaptureService(database);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		examMetadataPane = new ExamMetadataPane(primaryStage, config.pdfDataRoot(), curriculumSelectionModel,
				new ExamMetadataOptionsRepository(), examImporter, this::allowExamImportConfirmation, this::openExamPdf,
				pdfWorkspace::setSelectionCursorEnabled, this::activateExamSubject);
		curriculumSelectorPane = createCurriculumSelectorPane();
		answerCapturePane = new AnswerCapturePane(primaryStage, questionRepository, answerWriter, answerPdfPicker,
				this::openAnswerPdf, () -> pdfWorkspace.showDocument(PdfWorkspacePane.DocumentMode.ANSWER),
				this::allowAnswerCaptureTransition, () -> clearCaptureSelection(CaptureSelectionOwner.ANSWER),
				questionExtractor, pdfWorkspace::getAnswerPdfSession,
				(selected, completed) -> pdfWorkspace.openAnswerPdfAsync(selected.path(), completed));
		answerCapturePane.refreshQuestions();
		SharedContextCapturePane sharedContextCapturePane = new SharedContextCapturePane(
				new SqliteSharedQuestionContextRepository(database), examMetadataPane::getBooklet, questionExtractor,
				pdfWorkspace::getExamPdfSession, () -> clearCaptureSelection(CaptureSelectionOwner.SHARED_CONTEXT),
				() -> !captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		questionCapturePane = new QuestionCapturePane(questionRepository, sourceQuestionRepository,
				questionCaptureService, sharedContextCapturePane, questionExtractor, curriculumSelectionModel,
				curriculumSelectorPane, examMetadataPane::getBooklet, pdfWorkspace::getExamPdfSession,
				question -> activateImportedQuestion(question, config), this::confirmDiscardAcceptedQuestionRegions,
				this::transferQuestionSelectionToSharedContext,
				() -> clearCaptureSelection(CaptureSelectionOwner.QUESTION), answerCapturePane::refreshQuestions);
		questionCapturePane.refreshImportedQuestions();
	}

	private boolean isRegionSelectionAvailable(PdfWorkspacePane.DocumentMode documentMode) {
		if (documentMode == PdfWorkspacePane.DocumentMode.VIEWER) {
			return false;
		}
		if (documentMode == PdfWorkspacePane.DocumentMode.ANSWER) {
			return answerCapturePane.canCaptureRegions();
		}
		return examMetadataPane.getBooklet() != null && !questionCapturePane.isSaveInProgress();
	}

	private void offerQuestionRecaptureAfterPreambleConversion(Stage primaryStage, Question question,
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
		alert.setTitle("Question Preamble Converted");
		alert.setHeaderText("The captured preamble has been converted to ordinary question regions.");
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
		boolean recaptureStarted = questionCapturePane.recaptureQuestion(question, completedHandler);
		if (!recaptureStarted) {
			completedHandler.run();
		}
	}

	private void offerQuestionRecaptureAfterPreambleConversion(Stage primaryStage, QuestionSearchDialog searchDialog,
			Question question, CurriculumRepository curriculumRepository,
			LegacyQuestionMetadataService metadataService) {
		Runnable resumeSearch = () -> resumeSearchAfterEdit(primaryStage, searchDialog, question.getId(),
				curriculumRepository, metadataService);
		offerQuestionRecaptureAfterPreambleConversion(primaryStage, question, resumeSearch);
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
		authoringStage.setScene(new Scene(authoringPane, 1400, 840));
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
		int suffix = 2;
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
		int suffix = 2;
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
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("About Exam Question Bank");
		alert.setHeaderText("Exam Question Bank");
		alert.setContentText("""
				An application for importing, classifying, capturing and managing examination questions.
				""");
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

	private void showExamImport() {
		if (pdfWorkspace.getDisplayedDocument() == PdfWorkspacePane.DocumentMode.VIEWER) {
			showAlert(Alert.AlertType.WARNING, "Import Exam", "Close the viewer PDF first.",
					"An exam cannot be imported while an unrelated PDF is open in viewer mode.");
			return;
		}
		examMetadataPane.refreshSubjects();
		examMetadataPane.beginImport();
		examImportDialog.showAndWait();
	}

	private void showLegacyQuestionImportResult(int importedBooklets, LegacyQuestionImportResult importResult) {
		String message = """
				Exam booklets imported: %d
				Questions imported: %d
				Questions already present: %d
				Answers imported: %d
				""".formatted(importedBooklets, importResult.insertedQuestions(), importResult.existingQuestions(),
				importResult.insertedAnswers());
		showAlert(Alert.AlertType.INFORMATION, "Legacy Question Import", "Legacy question metadata imported.", message);
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
			ApplicationConfig.saveDataRoot(PROPERTIES_FILE, dataRoot);
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
		QuestionCorpusAuditDialog dialog = new QuestionCorpusAuditDialog(primaryStage, questionRepository.findAll());
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(
				new SqliteDatabase(config.databasePath()));
		dialog.setBulkResponseTypeHandler((questions, responseType) -> {
			try {
				metadataService.resolveUnknownResponseTypes(questions, responseType);
				questionCapturePane.refreshImportedQuestions();
				answerCapturePane.refreshQuestions();
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
				answerCapturePane.editAnswer(question, () -> {
				});
			} else {
				answerCapturePane.captureAnswer(question);
			}
		}
		}
	}

	private void showQuestionSearch(Stage primaryStage, ApplicationConfig config) {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(questionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(config.pdfDataRoot()),
				questionExtractor);
		LegacyQuestionMetadataService metadataService = new LegacyQuestionMetadataService(database);
		QuestionSearchDialog dialog = new QuestionSearchDialog(primaryStage, curriculumRepository, retrievalService,
				previewService);
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

		if (request.target() == QuestionSearchDialog.EditTarget.SHARED_PREAMBLE) {

			boolean correctionStarted = questionCapturePane.recaptureSharedContext(question,
					() -> resumeSearchAfterEdit(primaryStage, dialog, question.getId(), curriculumRepository,
							metadataService));

			if (!correctionStarted) {
				showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
			}
			return;
		}

		if (request.target() == QuestionSearchDialog.EditTarget.QUESTION) {
			boolean editingStarted = questionCapturePane.editQuestion(question,
					() -> resumeSearchAfterEdit(primaryStage, dialog, question.getId(), curriculumRepository,
							metadataService));

			if (!editingStarted) {
				showQuestionSearchDialog(primaryStage, dialog, curriculumRepository, metadataService);
			}
			return;
		}

		boolean editingStarted = answerCapturePane.editAnswer(question, () -> resumeSearchAfterEdit(primaryStage,
				dialog, question.getId(), curriculumRepository, metadataService));

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
		RevisionExportDialog dialog = new RevisionExportDialog(primaryStage, curriculumSelectionModel.getSubjects(),
				curriculumSelectionModel.getSubject());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		Subject subject = dialog.getSelectedSubject();
		Path destinationParent = dialog.getDestinationParent();
		if (subject == null || destinationParent == null) {
			return;
		}
		Path destination = revisionExportDestination(destinationParent, subject);
		startRevisionExport(primaryStage, config, subject, destination);
	}

	private void showRevisionExportSuccess(RevisionExportResult result) {
		String message = """
				Export location:
				%s

				Applicable questions: %d
				Exportable questions: %d
				Awaiting question capture: %d
				Questions without answers: %d
				Preamble review flags: %d
				""".formatted(result.getDestination(), result.getStatistics().getUniqueApplicableQuestions(),
				result.getStatistics().getRenderableQuestions(),
				result.getStatistics().getMissingQuestionRegionQuestions(),
				result.getStatistics().getQuestionsWithoutAnswers(),
				result.getStatistics().getPreambleReviewQuestions());
		showAlert(Alert.AlertType.INFORMATION, "Export Revision HTML", "Revision website exported successfully.",
				message);
	}

	private void showScormExportDialog(Stage primaryStage, ApplicationConfig config) {
		if (scormExportRunning) {
			return;
		}
		ScormExportDialog dialog = new ScormExportDialog(primaryStage, curriculumSelectionModel.getSubjects(),
				curriculumSelectionModel.getSubject());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		Subject subject = dialog.getSelectedSubject();
		Path destinationParent = dialog.getDestinationParent();
		if (subject == null || destinationParent == null) {
			return;
		}
		Path destination = scormExportDestination(destinationParent, subject);
		startScormExport(primaryStage, config, subject, destination);
	}

	private void showScormExportSuccess(ScormExportResult result) {
		String message = """
				SCORM ZIP:
				%s

				Applicable questions: %d
				Exportable questions: %d
				Awaiting question capture: %d
				Questions without answers: %d
				Preamble review flags: %d
				""".formatted(result.getDestination(), result.getStatistics().getUniqueApplicableQuestions(),
				result.getStatistics().getRenderableQuestions(),
				result.getStatistics().getMissingQuestionRegionQuestions(),
				result.getStatistics().getQuestionsWithoutAnswers(),
				result.getStatistics().getPreambleReviewQuestions());
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

	private void startRevisionExport(Stage primaryStage, ApplicationConfig config, Subject subject, Path destination) {
		if (revisionExportRunning) {
			return;
		}
		revisionExportRunning = true;
		if (revisionExportMenuItem != null) {
			revisionExportMenuItem.setDisable(true);
		}
		RevisionExportService exportService = createRevisionExportService(config);
		RevisionExportRequest request = new RevisionExportRequest(subject, destination);
		Task<RevisionExportResult> task = new Task<RevisionExportResult>() {

			@Override
			protected RevisionExportResult call() throws Exception {
				updateMessage("Starting export...");
				updateProgress(-1, 1);
				return exportService.export(request, (message, completed, total) -> {
					updateMessage(message);
					if (total > 0) {
						updateProgress(completed, total);
					} else {
						updateProgress(-1, 1);
					}
				});
			}
		};
		Alert progressAlert = createExportProgressAlert(primaryStage, task, "Export Revision HTML",
				"Creating revision website...", "Starting export...");
		task.setOnSucceeded(_ -> completeRevisionExport(task, progressAlert));
		task.setOnFailed(_ -> failRevisionExport(task, progressAlert));
		progressAlert.show();
		Thread thread = new Thread(task, "revision-html-export");
		thread.setDaemon(true);
		thread.start();
	}

	private void startScormExport(Stage primaryStage, ApplicationConfig config, Subject subject, Path destination) {
		if (scormExportRunning) {
			return;
		}
		scormExportRunning = true;
		if (scormExportMenuItem != null) {
			scormExportMenuItem.setDisable(true);
		}
		ScormExportService exportService = createScormExportService(config);
		ScormExportRequest request = new ScormExportRequest(subject, destination);
		Task<ScormExportResult> task = new Task<ScormExportResult>() {

			@Override
			protected ScormExportResult call() throws Exception {
				updateMessage("Starting SCORM export...");
				updateProgress(-1, 1);
				return exportService.export(request, (message, completed, total) -> {
					updateMessage(message);
					if (total > 0) {
						updateProgress(completed, total);
					} else {
						updateProgress(-1, 1);
					}
				});
			}
		};
		Alert progressAlert = createExportProgressAlert(primaryStage, task, "Export Revision SCORM",
				"Creating SCORM package...", "Starting SCORM export...");
		task.setOnSucceeded(_ -> completeScormExport(task, progressAlert));
		task.setOnFailed(_ -> failScormExport(task, progressAlert));
		progressAlert.show();
		Thread thread = new Thread(task, "revision-scorm-export");
		thread.setDaemon(true);
		thread.start();
	}

	private boolean transferQuestionSelectionToSharedContext() {
		if (!captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION)) {
			return false;
		}
		captureSelectionState.claim(CaptureSelectionOwner.SHARED_CONTEXT);
		return true;
	}

	private enum BackupFailureDecision {
		RETRY, EXIT_WITHOUT_BACKUP, CANCEL_EXIT
	}
}
