package au.edu.eq.questionbank.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.curriculum.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletImportRequest;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
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
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SourceQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedQuestionContextRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteSourceQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumImportConflictException;
import au.edu.eq.questionbank.repository.curriculum.CurriculumImportResult;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumImporter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
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
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.SubtopicMappingEvidenceService;
import au.edu.eq.questionbank.service.curriculum.TfIdfCurriculumMappingSuggester;
import au.edu.eq.questionbank.service.retrieval.CurriculumSearchNodeExpansionService;
import au.edu.eq.questionbank.service.retrieval.QuestionPreviewService;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalService;
import au.edu.eq.questionbank.service.revision.RevisionCorpusBuilder;
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
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Composition root for the Exam Question Bank desktop application.
 * <p>
 * Workflow state and controls are delegated to focused panes for exam metadata,
 * PDF display, question capture, and answer capture.
 */
public class QuestionBankApplication extends Application {

	private static final double SECTION_SPACING = 10.0;
	private static final double PREVIEW_PANE_WIDTH = 500.0;
	private static final double SCENE_WIDTH = 1400.0;
	private static final double SCENE_HEIGHT = 840.0;
	private static final Insets PREVIEW_PANE_PADDING = new Insets(10);
	private static final Path PROPERTIES_FILE = Path.of("questionbank.properties");
	private QuestionRepository questionRepository;
	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();
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
			showAlert(Alert.AlertType.WARNING, "Import Exam", "Question selection pending",
					"Add or clear the current question selection before importing another exam.");
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

	private void configurePdfWorkspace() {
		pdfWorkspace.setSelectionAvailable(this::isRegionSelectionAvailable);
		pdfWorkspace.setSelectionHandler(this::handleRegionSelection);
		pdfWorkspace.setPageNavigationAllowed(this::allowPdfPageNavigation);
	}

	private void configurePrimaryStage(Stage primaryStage, ApplicationConfig config) {
		primaryStage.setOnCloseRequest(event -> {
			event.consume();
			requestApplicationExit(primaryStage);
		});
		showStage(primaryStage, createRootLayout(primaryStage, config));
		examImportDialog = new ExamImportDialog(primaryStage, examMetadataPane);
	}

	private void configureShutdown(ApplicationConfig config) {
		DefaultBackupService automaticBackupService = new DefaultBackupService(config, applicationVersion());
		shutdownCoordinator = new ShutdownCoordinator(automaticBackupService, BackupRequest.automaticDatabase(config),
				new AutomaticBackupRetention(), pdfWorkspace);
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
		curriculumMenu.getItems().addAll(createMenuItem("_Import...", () -> importCurriculum(primaryStage, config)),
				createMenuItem("_Review Mappings...", () -> reviewCurriculumMappings(primaryStage, config)));
		return curriculumMenu;
	}

	private CurriculumSelectorPane createCurriculumSelectorPane() {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);
		selectorPane.selectedSubjectProperty().addListener((observable, oldSubject, newSubject) -> {
			examMetadataPane.invalidateForSubjectChange(newSubject);
		});
		return selectorPane;
	}

	private Menu createExamMenu(Stage primaryStage, ApplicationConfig config) {
		Menu examMenu = createMenu("_Exam");
		examMenu.getItems().addAll(createMenuItem("_Import...", this::showExamImport), createMenuItem(
				"Import _Legacy Question Metadata...", () -> importLegacyQuestionMetadata(primaryStage, config)));
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
		item.setOnAction(event -> action.run());
		item.setMnemonicParsing(true);
		return item;
	}

	private void createMissingLegacyBooklets(ApplicationConfig config, Subject subject,
			List<LegacyBookletImportRequest> requests) throws IOException, SQLException {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		SqliteExamImporter examImporter = new SqliteExamImporter(database, new SqliteExamWriter(database));
		PdfStore pdfStore = new PdfStore(config.pdfDataRoot());
		for (LegacyBookletImportRequest request : requests) {
			LegacyBookletRequirement requirement = request.requirement();
			Path storedPath = pdfStore.importExamPdf(request.pdfPath(), subject.getName(), requirement.providerName(),
					requirement.year());
			String relativePath = config.pdfDataRoot().relativize(storedPath).toString();
			examImporter.importExam(subject, requirement.providerName(), requirement.year(), request.assessmentName(),
					requirement.bookletName(), relativePath);
		}
	}

	private VBox createPreviewPane() {
		VBox previewPane = new VBox(SECTION_SPACING, curriculumSelectorPane, questionCapturePane, answerCapturePane);
		previewPane.setPadding(PREVIEW_PANE_PADDING);
		setFixedWidth(previewPane, PREVIEW_PANE_WIDTH);
		return previewPane;
	}

	private ScrollPane createPreviewScrollPane() {
		ScrollPane scrollPane = new ScrollPane(createPreviewPane());
		scrollPane.setFitToWidth(true);
		scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		scrollPane.setMinHeight(0);
		scrollPane.setPrefWidth(PREVIEW_PANE_WIDTH + 18);
		return scrollPane;
	}

	private Menu createQuestionMenu(Stage primaryStage, ApplicationConfig config) {
		Menu questionMenu = createMenu("_Questions");
		MenuItem captureNewItem = createMenuItem("Capture _New Questions", questionCapturePane::showNewQuestionCapture);
		captureNewItem.setId("capture-new-questions");
		MenuItem captureImportedItem = createMenuItem("Capture _Imported Questions",
				questionCapturePane::showImportedQuestionCapture);
		captureImportedItem.setId("capture-imported-questions");
		MenuItem searchItem = createMenuItem("_Search...", () -> showQuestionSearch(primaryStage, config));
		questionMenu.getItems().addAll(captureNewItem, captureImportedItem, new SeparatorMenuItem(), searchItem);
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
		return new RevisionExportService(corpusBuilder, new RevisionQuestionAssetRenderer(pdfStore, extractor),
				new RevisionAnswerAssetRenderer(pdfStore, extractor), new RevisionExportValidator());
	}

	private BorderPane createRootLayout(Stage primaryStage, ApplicationConfig config) {
		BorderPane root = new BorderPane();
		root.setTop(createMenuBar(primaryStage, config));
		previewScrollPane = createPreviewScrollPane();
		root.setLeft(previewScrollPane);
		root.setCenter(pdfWorkspace);
		return root;
	}

	private ScormExportService createScormExportService(ApplicationConfig config) {
		return new ScormExportService(createRevisionExportService(config), new ScormManifestWriter(),
				new ScormSchemaSupport(), new ScormPackageValidator(), new ScormZipWriter());
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

	private void handleRegionSelection(PdfWorkspacePane.RegionSelection selection) {
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.ANSWER) {
			captureSelectionState.claim(CaptureSelectionOwner.ANSWER);
			answerCapturePane.acceptSelection(selection);
			return;
		}
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.EXAM) {
			if (questionCapturePane.isCapturingSharedContext()) {
				captureSelectionState.claim(CaptureSelectionOwner.SHARED_CONTEXT);
				questionCapturePane.acceptSharedContextSelection(selection);
			} else {
				captureSelectionState.claim(CaptureSelectionOwner.QUESTION);
				questionCapturePane.acceptSelection(selection);
			}
		}
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
			return Optional.of(Integer.valueOf(0));
		}
		LegacyBookletImportDialog bookletDialog = new LegacyBookletImportDialog(primaryStage, missingBooklets);
		Optional<ButtonType> bookletResult = bookletDialog.showAndWait();
		if (bookletResult.isEmpty()
				|| bookletResult.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
			return Optional.empty();
		}
		List<LegacyBookletImportRequest> requests = bookletDialog.getRequests();
		createMissingLegacyBooklets(config, subject, requests);
		List<LegacyBookletRequirement> stillMissing = importer.findMissingBooklets(dialog.getSelectedFile(),
				subject.getName(), syllabusVersion.getName());
		if (!stillMissing.isEmpty()) {
			throw new IllegalStateException("Required exam booklets are still missing after booklet import.");
		}
		return Optional.of(Integer.valueOf(requests.size()));
	}

	private void initialiseCaptureWorkflow(Stage primaryStage, ApplicationConfig config, SqliteDatabase database,
			PdfFilePicker answerPdfPicker) {
		questionRepository = new SqliteQuestionRepository(database);
		SourceQuestionRepository sourceQuestionRepository = new SqliteSourceQuestionRepository(database);
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
				questionExtractor, pdfWorkspace::getAnswerPdfSession);
		answerCapturePane.refreshQuestions();
		SharedContextCapturePane sharedContextCapturePane = new SharedContextCapturePane(
				new SqliteSharedQuestionContextRepository(database), examMetadataPane::getBooklet, questionExtractor,
				pdfWorkspace::getExamPdfSession, () -> clearCaptureSelection(CaptureSelectionOwner.SHARED_CONTEXT),
				() -> !captureSelectionState.isOwnedBy(CaptureSelectionOwner.QUESTION));
		questionCapturePane = new QuestionCapturePane(questionRepository, sourceQuestionRepository,
				sharedContextCapturePane, questionExtractor, curriculumSelectionModel, curriculumSelectorPane,
				examMetadataPane::getBooklet, pdfWorkspace::getExamPdfSession,
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
			return answerCapturePane.hasAnswerFile();
		}
		return examMetadataPane.getBooklet() != null;
	}

	private void openAnswerPdf(SelectedPdf selectedPdf) {
		pdfWorkspace.openAnswerPdf(selectedPdf.path());
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

	private void reviewCurriculumMappings(Stage primaryStage, ApplicationConfig config) {
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			CurriculumRepository repository = new SqliteCurriculumRepository(database);
			CurriculumMappingRepository mappingRepository = new SqliteCurriculumMappingRepository(database);
			CurriculumMappingSuggester descriptorSuggester = new TfIdfCurriculumMappingSuggester(repository);
			CurriculumMappingSuggester subtopicSuggester = new ConfirmedDescriptorSubtopicMappingSuggester(repository,
					mappingRepository);
			CurriculumMappingReviewRepository reviewRepository = new SqliteCurriculumMappingReviewRepository(database);
			SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
			SubtopicMappingEvidenceService subtopicEvidenceService = new SubtopicMappingEvidenceService(repository,
					reviewRepository);
			CurriculumMappingReviewDialog dialog = new CurriculumMappingReviewDialog(primaryStage, repository,
					descriptorSuggester, subtopicSuggester, subtopicEvidenceService, reviewRepository,
					mappingRepository, reviewWriter);
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

	private void setFixedWidth(Region region, double width) {
		region.setPrefWidth(width);
		region.setMinWidth(width);
		region.setMaxWidth(width);
	}

	private void setViewerMode(boolean viewerMode) {
		previewScrollPane.setVisible(!viewerMode);
		previewScrollPane.setManaged(!viewerMode);
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

	private void showQuestionSearch(Stage primaryStage, ApplicationConfig config) {
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		CurriculumRepository curriculumRepository = new SqliteCurriculumRepository(database);
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		QuestionRetrievalService retrievalService = new QuestionRetrievalService(questionRepository,
				new CurriculumSearchNodeExpansionService(curriculumRepository));
		QuestionPreviewService previewService = new QuestionPreviewService(new PdfStore(config.pdfDataRoot()),
				questionExtractor);
		QuestionSearchDialog dialog = new QuestionSearchDialog(primaryStage, curriculumRepository, retrievalService,
				previewService);
		showQuestionSearchDialog(dialog);
	}

	private void showQuestionSearchDialog(QuestionSearchDialog dialog) {
		Optional<QuestionSearchDialog.EditRequest> result = dialog.showAndWait();
		if (result.isEmpty()) {
			dialog.dispose();
			return;
		}
		QuestionSearchDialog.EditRequest request = result.get();
		Question question = request.question();
		if (request.target() == QuestionSearchDialog.EditTarget.QUESTION) {
			boolean editingStarted = questionCapturePane.editQuestion(question, () -> {
				dialog.refreshAfterEdit(question.getId());
				showQuestionSearchDialog(dialog);
			});
			if (!editingStarted) {
				showQuestionSearchDialog(dialog);
			}
			return;
		}
		boolean editingStarted = answerCapturePane.editAnswer(question, () -> {
			dialog.refreshAfterEdit(question.getId());
			showQuestionSearchDialog(dialog);
		});
		if (!editingStarted) {
			showQuestionSearchDialog(dialog);
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
		Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
		progressAlert.initOwner(primaryStage);
		progressAlert.setTitle("Export Revision HTML");
		progressAlert.setHeaderText("Creating revision website...");
		Label progressLabel = new Label("Starting export...");
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
		task.setOnSucceeded(event -> {
			finishRevisionExport();
			progressAlert.close();
			showRevisionExportSuccess(task.getValue());
		});
		task.setOnFailed(event -> {
			finishRevisionExport();
			progressAlert.close();
			showAlert(Alert.AlertType.ERROR, "Export Revision HTML", "The revision export could not be completed.",
					failureMessage(task.getException()));
		});
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
		Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
		progressAlert.initOwner(primaryStage);
		progressAlert.setTitle("Export Revision SCORM");
		progressAlert.setHeaderText("Creating SCORM package...");
		Label progressLabel = new Label("Starting SCORM export...");
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
		task.setOnSucceeded(event -> {
			finishScormExport();
			progressAlert.close();
			showScormExportSuccess(task.getValue());
		});
		task.setOnFailed(event -> {
			finishScormExport();
			progressAlert.close();
			showAlert(Alert.AlertType.ERROR, "Export Revision SCORM", "The SCORM export could not be completed.",
					failureMessage(task.getException()));
		});
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
