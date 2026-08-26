package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.QuestionExtractor;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.InMemoryQuestionRepository;
import au.edu.eq.questionbank.repository.QuestionRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ScrollPane;
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

	public static void main(String[] args) {
		launch(args);
	}

	private final QuestionRepository questionRepository = new InMemoryQuestionRepository();
	private final QuestionExtractor questionExtractor = new QuestionExtractor();

	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();
	private CurriculumSelectionModel curriculumSelectionModel;
	private CurriculumSelectorPane curriculumSelectorPane;
	private ExamMetadataPane examMetadataPane;
	private QuestionCapturePane questionCapturePane;

	private AnswerCapturePane answerCapturePane;

	@Override
	public void start(Stage stage) throws Exception {
		ApplicationConfig config;
		try {
			config = ApplicationConfig.load(Path.of("questionbank.properties"));
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

	private CurriculumSelectorPane createCurriculumSelectorPane() {
		CurriculumSelectorPane selectorPane = new CurriculumSelectorPane(curriculumSelectionModel);
		Subject subject = curriculumSelectionModel.getSubject();
		examMetadataPane.setSelectedSubject(subject);
		selectorPane.selectedSubjectProperty().addListener((observable, oldSubject, newSubject) -> {
			examMetadataPane.setSelectedSubject(newSubject);
		});
		return selectorPane;
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

	private BorderPane createRootLayout() {
		BorderPane root = new BorderPane();
		root.setTop(examMetadataPane);
		root.setLeft(createPreviewScrollPane());
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

	private boolean isRegionSelectionAvailable(PdfWorkspacePane.DocumentMode documentMode) {
		return documentMode == PdfWorkspacePane.DocumentMode.ANSWER ? answerCapturePane.hasAnswerFile()
				: examMetadataPane.getBooklet() != null;
	}

	private void openAnswerPdf(SelectedPdf selectedPdf) {
		pdfWorkspace.openAnswerPdf(selectedPdf.path());
	}

	private void openExamPdf(SelectedPdf selectedPdf) {
		pdfWorkspace.openExamPdf(selectedPdf.path());
		questionCapturePane.clearForNewPdf();
	}

	private void setFixedWidth(Region region, double width) {
		region.setPrefWidth(width);
		region.setMinWidth(width);
		region.setMaxWidth(width);
	}

	private void showAlert(Alert.AlertType type, String title, String header, String message) {
		Alert alert = new Alert(type);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.setContentText(message);
		alert.showAndWait();
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

	private void startApplication(Stage primaryStage, ApplicationConfig config) throws IOException {
		curriculumSelectionModel = new CurriculumSelectionModelFactory().create(config);
		PdfFilePicker answerPdfPicker = new PdfFilePicker(config.pdfDataRoot());
		examMetadataPane = new ExamMetadataPane(primaryStage, config.pdfDataRoot(), curriculumSelectionModel,
				new ExamMetadataOptionsRepository(), pdfWorkspace::hasExamPdf, this::openExamPdf,
				pdfWorkspace::setSelectionCursorEnabled);
		curriculumSelectorPane = createCurriculumSelectorPane();
		answerCapturePane = new AnswerCapturePane(primaryStage, questionRepository, answerPdfPicker,
				this::openAnswerPdf, pdfWorkspace::clearSelection, questionExtractor,
				pdfWorkspace::getAnswerPdfSession);
		questionCapturePane = new QuestionCapturePane(questionRepository, questionExtractor, curriculumSelectionModel,
				curriculumSelectorPane, examMetadataPane::getBooklet, pdfWorkspace::getExamPdfSession,
				pdfWorkspace::clearSelection, answerCapturePane::refreshUnansweredQuestions);

		pdfWorkspace.setSelectionAvailable(this::isRegionSelectionAvailable);
		pdfWorkspace.setSelectionHandler(this::handleRegionSelection);
		pdfWorkspace.setPageChangeHandler(() -> {
			questionCapturePane.clearCurrentSelection();
			answerCapturePane.clearCurrentSelectionForPageChange();
		});

		showStage(primaryStage, createRootLayout());
	}

}
