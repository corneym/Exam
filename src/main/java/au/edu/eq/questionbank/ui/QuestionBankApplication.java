package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.curriculum.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionImportResult;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionMetadataImporter;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.QuestionRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
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
import au.edu.eq.questionbank.service.curriculum.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.curriculum.TfIdfCurriculumMappingSuggester;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModelFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Composition root for the Exam Question Bank desktop application.
 * <p>
 * Workflow state and controls are delegated to focused panes for exam metadata,
 * PDF display, question capture, and answer capture.
 */
public class QuestionBankApplication extends Application {

	private static final double SECTION_SPACING = 10.0;
	private static final double PREVIEW_PANE_WIDTH = 330.0;
	private static final double SCENE_WIDTH = 1400.0;
	private static final double SCENE_HEIGHT = 840.0;
	private static final Insets PREVIEW_PANE_PADDING = new Insets(10);
	private static final Path PROPERTIES_FILE = Path.of("questionbank.properties");

	/**
	 * Launches the desktop application.
	 *
	 * @param args command-line arguments passed to JavaFX
	 */
	public static void main(String[] args) {
		launch(args);
	}

	private QuestionRepository questionRepository;

	private final QuestionExtractor questionExtractor = new QuestionExtractor();
	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();

	private CurriculumSelectionModel curriculumSelectionModel;
	private CurriculumSelectorPane curriculumSelectorPane;
	private ExamMetadataPane examMetadataPane;
	private QuestionCapturePane questionCapturePane;
	private AnswerCapturePane answerCapturePane;
	private ScrollPane previewScrollPane;
	private ExamImportDialog examImportDialog;

	/**
	 * Creates the desktop application instance initialized by JavaFX.
	 */
	public QuestionBankApplication() {
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
		pdfWorkspace.close();
	}

	private void activateExamSubject(Subject subject) {
		curriculumSelectorPane.selectSubject(subject);
	}

	private void closeViewerPdf() {
		pdfWorkspace.closeViewerPdf();
		setViewerMode(false);
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

	private Menu createExportMenu() {
		Menu exportMenu = createMenu("E_xport");
		MenuItem exportPlaceholder = new MenuItem("No export options yet");
		exportPlaceholder.setDisable(true);
		exportMenu.getItems().add(exportPlaceholder);
		return exportMenu;
	}

	private Menu createFileMenu(Stage primaryStage, ApplicationConfig config) {
		Menu fileMenu = createMenu("_File");
		Menu openMenu = createMenu("_Open");
		openMenu.getItems().add(createMenuItem("_PDF...", () -> openViewerPdf(primaryStage, config)));
		fileMenu.getItems().addAll(openMenu, createMenuItem("_Close PDF", this::closeViewerPdf),
				new SeparatorMenuItem(), createMenuItem("Op_tions...", () -> showOptions(primaryStage, config)),
				new SeparatorMenuItem(), createMenuItem("E_xit", Platform::exit));
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
				createCurriculumMenu(primaryStage, config), createExportMenu(), createHelpMenu(config));
		return menuBar;
	}

	private MenuItem createMenuItem(String text, Runnable action) {
		MenuItem item = new MenuItem(text);
		item.setOnAction(event -> action.run());
		item.setMnemonicParsing(true);
		return item;
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

	private BorderPane createRootLayout(Stage primaryStage, ApplicationConfig config) {
		BorderPane root = new BorderPane();
		root.setTop(createMenuBar(primaryStage, config));
		previewScrollPane = createPreviewScrollPane();
		root.setLeft(previewScrollPane);
		root.setCenter(pdfWorkspace);
		return root;
	}

	private void handleRegionSelection(PdfWorkspacePane.RegionSelection selection) {
		if (selection.documentMode() == PdfWorkspacePane.DocumentMode.ANSWER) {
			answerCapturePane.acceptSelection(selection);
		} else {
			questionCapturePane.acceptSelection(selection);
		}
	}

	private void importCurriculum(Stage primaryStage, ApplicationConfig config) {
		CurriculumImportDialog dialog = new CurriculumImportDialog(primaryStage);
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
			importer.importSyllabus(dialog.getSubjectName(), dialog.getVersionName(), dialog.isCurrent(), rows);
			curriculumSelectorPane.refreshSubjects();
			showAlert(Alert.AlertType.INFORMATION, "Curriculum Import", "Curriculum imported successfully.",
					dialog.getSubjectName() + " " + dialog.getVersionName());
		} catch (IOException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Import", "Could not read the Excel file.", e.getMessage());
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
			LegacyQuestionImportResult importResult = importer.importWorkbook(dialog.getSelectedFile(),
					subject.getName(), syllabusVersion.getName());
			answerCapturePane.refreshUnansweredQuestions();
			String message = """
					Questions imported: %d
					Questions already present: %d
					Answers imported: %d
					""".formatted(importResult.insertedQuestions(), importResult.existingQuestions(),
					importResult.insertedAnswers());
			showAlert(Alert.AlertType.INFORMATION, "Legacy Question Import", "Legacy question metadata imported.",
					message);
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

	private void reviewCurriculumMappings(Stage primaryStage, ApplicationConfig config) {
		try {
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			CurriculumRepository repository = new SqliteCurriculumRepository(database);
			CurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);
			CurriculumMappingReviewRepository reviewRepository = new SqliteCurriculumMappingReviewRepository(database);
			CurriculumMappingRepository mappingRepository = new SqliteCurriculumMappingRepository(database);
			SqliteCurriculumMappingReviewWriter reviewWriter = new SqliteCurriculumMappingReviewWriter(database);
			CurriculumMappingReviewDialog dialog = new CurriculumMappingReviewDialog(primaryStage, repository,
					suggester, reviewRepository, mappingRepository, reviewWriter);
			dialog.showAndWait();
		} catch (IllegalStateException e) {
			showAlert(Alert.AlertType.ERROR, "Curriculum Mapping", "Could not load curriculum mappings.",
					e.getMessage());
		}
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

	private void showExamImport() {
		if (pdfWorkspace.getDisplayedDocument() == PdfWorkspacePane.DocumentMode.VIEWER) {
			showAlert(Alert.AlertType.WARNING, "Import Exam", "Close the viewer PDF first.",
					"An exam cannot be imported while an unrelated PDF is open in viewer mode.");
			return;
		}
		examImportDialog.showAndWait();
	}

	private void showOptions(Stage primaryStage, ApplicationConfig config) {
		OptionsDialog dialog = new OptionsDialog(primaryStage, config.dataRoot());
		Optional<ButtonType> result = dialog.showAndWait();
		if (result.isEmpty() || result.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
			return;
		}
		try {
			Path dataRoot = dialog.getDataRoot();
			ApplicationConfig.saveDataRoot(PROPERTIES_FILE, dataRoot);
			showAlert(Alert.AlertType.INFORMATION, "Options", "Options saved.",
					"The new data location will be used after the application is restarted.");
		} catch (IllegalArgumentException e) {
			showAlert(Alert.AlertType.ERROR, "Options", "The data location is invalid.", e.getMessage());
		} catch (IOException e) {
			showAlert(Alert.AlertType.ERROR, "Options", "Could not save the application options.", e.getMessage());
		}
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
		String applicationVersion = getClass().getPackage().getImplementationVersion();
		if (applicationVersion == null || applicationVersion.isBlank()) {
			applicationVersion = "Development build";
		}
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
		questionRepository = new SqliteQuestionRepository(database);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		SqliteAnswerWriter answerWriter = new SqliteAnswerWriter(database, examWriter);
		SqliteExamImporter examImporter = new SqliteExamImporter(database, examWriter);
		examMetadataPane = new ExamMetadataPane(primaryStage, config.pdfDataRoot(), curriculumSelectionModel,
				new ExamMetadataOptionsRepository(), examImporter, pdfWorkspace::hasExamPdf, this::openExamPdf,
				pdfWorkspace::setSelectionCursorEnabled, this::activateExamSubject);
		curriculumSelectorPane = createCurriculumSelectorPane();
		answerCapturePane = new AnswerCapturePane(primaryStage, questionRepository, answerWriter, answerPdfPicker,
				this::openAnswerPdf, pdfWorkspace::clearSelection, questionExtractor,
				pdfWorkspace::getAnswerPdfSession);
		answerCapturePane.refreshUnansweredQuestions();
		questionCapturePane = new QuestionCapturePane(questionRepository, questionExtractor, curriculumSelectionModel,
				curriculumSelectorPane, examMetadataPane::getBooklet, pdfWorkspace::getExamPdfSession,
				pdfWorkspace::clearSelection, answerCapturePane::refreshUnansweredQuestions);

		pdfWorkspace.setSelectionAvailable(this::isRegionSelectionAvailable);
		pdfWorkspace.setSelectionHandler(this::handleRegionSelection);
		pdfWorkspace.setPageChangeHandler(() -> {
			questionCapturePane.clearCurrentSelection();
			answerCapturePane.clearCurrentSelectionForPageChange();
		});

		showStage(primaryStage, createRootLayout(primaryStage, config));
		examImportDialog = new ExamImportDialog(primaryStage, examMetadataPane);
	}
}
