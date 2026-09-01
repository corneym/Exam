package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.importer.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.CurriculumImportRow;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.CurriculumMappingRepository;
import au.edu.eq.questionbank.repository.CurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.CurriculumRepository;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.QuestionRepository;
import au.edu.eq.questionbank.repository.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.SqliteCurriculumImporter;
import au.edu.eq.questionbank.repository.SqliteCurriculumMappingRepository;
import au.edu.eq.questionbank.repository.SqliteCurriculumMappingReviewRepository;
import au.edu.eq.questionbank.repository.SqliteCurriculumMappingReviewWriter;
import au.edu.eq.questionbank.repository.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.SqliteDatabase;
import au.edu.eq.questionbank.repository.SqliteExamImporter;
import au.edu.eq.questionbank.repository.SqliteExamWriter;
import au.edu.eq.questionbank.repository.SqliteQuestionRepository;
import au.edu.eq.questionbank.service.CurriculumMappingSuggester;
import au.edu.eq.questionbank.service.TfIdfCurriculumMappingSuggester;
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
		startApplication(stage, config);
	}

	@Override
	public void stop() throws Exception {
		pdfWorkspace.close();
	}

	private void closeViewerPdf() {
		pdfWorkspace.closeViewerPdf();
		setViewerMode(false);
	}

	private CurriculumSelectorPane createCurriculumSelectorPane() {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);
		Subject subject = curriculumSelectionModel.getSubject();
		examMetadataPane.setSelectedSubject(subject);
		selectorPane.selectedSubjectProperty().addListener((observable, oldSubject, newSubject) -> {
			examMetadataPane.setSelectedSubject(newSubject);
		});
		return selectorPane;
	}

	private MenuBar createMenuBar(Stage primaryStage, ApplicationConfig config) {
		MenuBar menuBar = new MenuBar();
		Menu fileMenu = new Menu("_File");
		Menu openMenu = new Menu("_Open");
		MenuItem openPdfItem = new MenuItem("_PDF...");
		openPdfItem.setOnAction(event -> openViewerPdf(primaryStage, config));
		openMenu.getItems().add(openPdfItem);
		MenuItem closePdfItem = new MenuItem("_Close PDF");
		closePdfItem.setOnAction(event -> closeViewerPdf());
		MenuItem optionsItem = new MenuItem("Op_tions...");
		optionsItem.setOnAction(event -> showOptions(primaryStage, config));
		MenuItem exitItem = new MenuItem("E_xit");
		exitItem.setOnAction(event -> Platform.exit());
		fileMenu.getItems().addAll(openMenu, closePdfItem, new SeparatorMenuItem(), optionsItem,
				new SeparatorMenuItem(), exitItem);
		Menu examMenu = new Menu("_Exam");
		MenuItem importExamItem = new MenuItem("_Import...");
		importExamItem.setOnAction(event -> showExamImport());
		examMenu.getItems().add(importExamItem);
		Menu curriculumMenu = new Menu("_Curriculum");
		MenuItem importCurriculumItem = new MenuItem("_Import...");
		importCurriculumItem.setOnAction(event -> importCurriculum(primaryStage, config));
		MenuItem mappingItem = new MenuItem("_Review Mappings...");
		mappingItem.setOnAction(event -> reviewCurriculumMappings(primaryStage, config));
		curriculumMenu.getItems().addAll(importCurriculumItem, mappingItem);
		Menu exportMenu = new Menu("E_xport");
		MenuItem exportPlaceholder = new MenuItem("No export options yet");
		exportPlaceholder.setDisable(true);
		exportMenu.getItems().add(exportPlaceholder);
		Menu helpMenu = new Menu("_Help");
		MenuItem aboutItem = new MenuItem("_About...");
		aboutItem.setOnAction(event -> showAbout());
		MenuItem versionItem = new MenuItem("_Version Information...");
		versionItem.setOnAction(event -> showVersionInformation(config));
		helpMenu.getItems().addAll(aboutItem, versionItem);
		menuBar.getMenus().addAll(fileMenu, examMenu, curriculumMenu, exportMenu, helpMenu);
		openPdfItem.setMnemonicParsing(true);
		closePdfItem.setMnemonicParsing(true);
		optionsItem.setMnemonicParsing(true);
		exitItem.setMnemonicParsing(true);
		importExamItem.setMnemonicParsing(true);
		importCurriculumItem.setMnemonicParsing(true);
		mappingItem.setMnemonicParsing(true);
		aboutItem.setMnemonicParsing(true);
		versionItem.setMnemonicParsing(true);
		fileMenu.setMnemonicParsing(true);
		openMenu.setMnemonicParsing(true);
		examMenu.setMnemonicParsing(true);
		curriculumMenu.setMnemonicParsing(true);
		exportMenu.setMnemonicParsing(true);
		helpMenu.setMnemonicParsing(true);
		return menuBar;
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
				pdfWorkspace::setSelectionCursorEnabled);
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
	}
}
