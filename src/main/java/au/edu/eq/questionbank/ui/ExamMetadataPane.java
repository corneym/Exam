package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Year;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Collects exam and booklet metadata and associates it with a selected source
 * PDF.
 */
final class ExamMetadataPane extends VBox {

	private record ExamMetadataInput(Subject subject, String providerName, Integer year, String assessmentName,
			String bookletName) {
	}

	private static final double CONTROL_SPACING = 8.0;
	private static final double PROVIDER_FIELD_WIDTH = 240.0;
	private static final double YEAR_FIELD_WIDTH = 100.0;
	private static final double ASSESSMENT_FIELD_WIDTH = 300.0;
	private static final double BOOKLET_FIELD_WIDTH = 240.0;
	private static final int YEAR_LOOKBACK_YEARS = 15;
	private static final Insets PANEL_PADDING = new Insets(12);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;-fx-padding: 12;";

	private final ComboBox<String> assessmentField = new ComboBox<>();
	private final ComboBox<String> bookletField = new ComboBox<>();
	private final ComboBox<String> providerField = new ComboBox<>();
	private final ComboBox<Subject> subjectField = new ComboBox<>();
	private final ComboBox<Integer> yearField = new ComboBox<>();
	private final Button choosePdfButton = new Button("Choose PDF...");
	private final Button setExamButton = new Button("Set Exam");
	private final Label selectedPdfLabel = new Label("No PDF selected");

	private final SqliteExamImporter examImporter;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final ExamMetadataOptionsRepository optionsRepository;
	private final PdfFilePicker pdfFilePicker;
	private final Path pdfDataRoot;
	private final BooleanSupplier examPdfAvailable;
	private final Consumer<SelectedPdf> examPdfHandler;
	private final Consumer<Boolean> selectionCursorHandler;
	private final Consumer<Subject> examSubjectHandler;

	private Path currentPdfPath;
	private ExamBooklet booklet;

	/**
	 * Creates the exam metadata workflow controls and persistence integration.
	 */
	ExamMetadataPane(Stage stage, Path pdfDataRoot, CurriculumSelectionModel curriculumSelectionModel,
			ExamMetadataOptionsRepository optionsRepository, SqliteExamImporter examImporter,
			BooleanSupplier examPdfAvailable, Consumer<SelectedPdf> examPdfHandler,
			Consumer<Boolean> selectionCursorHandler, Consumer<Subject> examSubjectHandler) {
		if (pdfDataRoot == null) {
			throw new NullPointerException("pdfDataRoot");
		}
		if (curriculumSelectionModel == null) {
			throw new NullPointerException("curriculumSelectionModel");
		}
		if (optionsRepository == null) {
			throw new NullPointerException("optionsRepository");
		}
		if (examImporter == null) {
			throw new NullPointerException("examImporter");
		}
		if (examPdfAvailable == null) {
			throw new NullPointerException("examPdfAvailable");
		}
		if (examPdfHandler == null) {
			throw new NullPointerException("examPdfHandler");
		}
		if (selectionCursorHandler == null) {
			throw new NullPointerException("selectionCursorHandler");
		}
		if (examSubjectHandler == null) {
			throw new NullPointerException("examSubjectHandler");
		}
		this.pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.optionsRepository = optionsRepository;
		this.examPdfAvailable = examPdfAvailable;
		this.examPdfHandler = examPdfHandler;
		this.examImporter = examImporter;
		this.selectionCursorHandler = selectionCursorHandler;
		this.examSubjectHandler = examSubjectHandler;
		pdfFilePicker = new PdfFilePicker(this.pdfDataRoot);
		configureFields();
		configureActions(stage);
		loadOptions();
		getChildren().add(createDetailsGrid());
		setSpacing(CONTROL_SPACING);
		setPadding(PANEL_PADDING);
	}

	private void applyInputToControls(ExamMetadataInput input) {
		providerField.setValue(input.providerName());
		assessmentField.setValue(input.assessmentName());
		bookletField.setValue(input.bookletName());
	}

	private void chooseExamPdf(Stage stage) {
		Path selectedPath = pdfFilePicker.chooseAnyPdf(stage, "Choose exam PDF");
		if (selectedPath == null) {
			return;
		}
		try {
			Path storedPath = copyIntoPdfDataRootIfNeeded(selectedPath);
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, pdfDataRoot);
			selectExamPdf(selectedPdf);
		} catch (IOException e) {
			showFileError(e.getMessage());
		}
	}

	private void configureActions(Stage stage) {
		choosePdfButton.setOnAction(event -> chooseExamPdf(stage));
		setExamButton.setOnAction(event -> setExamMetadata());
		choosePdfButton.setTooltip(new Tooltip("Choose the PDF containing the exam booklet."));
		setExamButton.setTooltip(new Tooltip("Apply these exam details before selecting question regions."));
	}

	private void configureFields() {
		providerField.setId("exam-provider");
		providerField.setPromptText("QCAA");
		providerField.setPrefWidth(PROVIDER_FIELD_WIDTH);
		providerField.setEditable(true);
		int currentYear = Year.now().getValue();
		for (int year = currentYear; year >= currentYear - YEAR_LOOKBACK_YEARS; year--) {
			yearField.getItems().add(year);
		}
		yearField.setId("exam-year");
		yearField.setPrefWidth(YEAR_FIELD_WIDTH);
		assessmentField.setId("exam-assessment");
		assessmentField.setPromptText("External Assessment");
		assessmentField.setPrefWidth(ASSESSMENT_FIELD_WIDTH);
		assessmentField.setEditable(true);
		bookletField.setId("exam-booklet");
		bookletField.setPromptText("Paper 1 MCQ");
		bookletField.setPrefWidth(BOOKLET_FIELD_WIDTH);
		bookletField.setEditable(true);
		subjectField.setId("exam-subject");
		subjectField.setPromptText("Select subject");
		subjectField.setPrefWidth(140.0);
		subjectField.getItems().setAll(curriculumSelectionModel.getSubjects());
		Subject currentSubject = curriculumSelectionModel.getSubject();
		if (currentSubject != null) {
			subjectField.setValue(currentSubject);
		}
		setExamButton.setId("set-exam");
		selectedPdfLabel.setWrapText(true);
	}

	private Path copyIntoPdfDataRootIfNeeded(Path selectedPath) throws IOException {
		Path normalisedPath = selectedPath.toAbsolutePath().normalize();
		if (normalisedPath.startsWith(pdfDataRoot)) {
			return normalisedPath;
		}
		Files.createDirectories(pdfDataRoot);
		Path destination = findAvailableDestination(normalisedPath.getFileName().toString());
		return Files.copy(normalisedPath, destination);
	}

	private GridPane createDetailsGrid() {
		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(12);
		grid.setStyle(BORDER_STYLE);
		grid.add(new Label("Subject:"), 0, 0);
		grid.add(subjectField, 1, 0);
		grid.add(new Label("PDF:"), 0, 1);
		grid.add(createPdfControls(), 1, 1);
		grid.add(new Label("Provider:"), 0, 2);
		grid.add(providerField, 1, 2);
		grid.add(new Label("Year:"), 0, 3);
		grid.add(yearField, 1, 3);
		grid.add(new Label("Assessment:"), 0, 4);
		grid.add(assessmentField, 1, 4);
		grid.add(new Label("Booklet:"), 0, 5);
		grid.add(bookletField, 1, 5);
		HBox buttons = new HBox(setExamButton);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		grid.add(buttons, 1, 6);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		GridPane.setHgrow(bookletField, Priority.ALWAYS);
		return grid;
	}

	private ExamBooklet createExamBooklet(ExamMetadataInput input) throws SQLException {
		String relativePath = pdfDataRoot.relativize(currentPdfPath).toString();
		return examImporter.importExam(input.subject(), input.providerName(), input.year(), input.assessmentName(),
				input.bookletName(), relativePath);
	}

	private HBox createPdfControls() {
		HBox controls = new HBox(CONTROL_SPACING, choosePdfButton, selectedPdfLabel);
		controls.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(selectedPdfLabel, Priority.ALWAYS);
		return controls;
	}

	private Path findAvailableDestination(String fileName) {
		Path destination = pdfDataRoot.resolve(fileName);
		if (!Files.exists(destination)) {
			return destination;
		}
		int dotPosition = fileName.lastIndexOf('.');
		String name = dotPosition > 0 ? fileName.substring(0, dotPosition) : fileName;
		String extension = dotPosition > 0 ? fileName.substring(dotPosition) : "";
		int number = 2;
		do {
			destination = pdfDataRoot.resolve(name + " (" + number + ")" + extension);
			number++;
		} while (Files.exists(destination));
		return destination;
	}

	private String findPrerequisiteError() {
		if (!examPdfAvailable.getAsBoolean()) {
			return "Choose a PDF first.";
		}
		if (subjectField.getValue() == null) {
			return "Select a subject before setting the exam.";
		}
		return null;
	}

	private boolean isComplete(ExamMetadataInput input) {
		return !input.providerName().isBlank() && input.year() != null && !input.assessmentName().isBlank()
				&& !input.bookletName().isBlank();
	}

	private void loadOptions() {
		providerField.getItems().setAll(optionsRepository.getProviders());
		assessmentField.getItems().setAll(optionsRepository.getAssessments());
		bookletField.getItems().setAll(optionsRepository.getBooklets());
	}

	private ExamMetadataInput readInput() {
		return new ExamMetadataInput(subjectField.getValue(), providerField.getEditor().getText().trim(),
				yearField.getValue(), assessmentField.getEditor().getText().trim(),
				bookletField.getEditor().getText().trim());
	}

	private void rememberOptions(ExamMetadataInput input) {
		optionsRepository.addProvider(input.providerName());
		optionsRepository.addAssessment(input.assessmentName());
		optionsRepository.addBooklet(input.bookletName());
		loadOptions();
	}

	private void setExamMetadata() {
		String prerequisiteError = findPrerequisiteError();
		if (prerequisiteError != null) {
			showError(prerequisiteError);
			return;
		}
		ExamMetadataInput input = readInput();
		if (!isComplete(input)) {
			showError("Complete all exam details.");
			return;
		}
		try {
			booklet = createExamBooklet(input);
			rememberOptions(input);
			applyInputToControls(input);
			selectionCursorHandler.accept(true);
			examSubjectHandler.accept(input.subject());
		} catch (SQLException e) {
			showDatabaseError(e.getMessage());
		}
	}

	private void showDatabaseError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText("Exam details could not be saved.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Exam details are incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private void showFileError(String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText("The exam PDF could not be imported.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	/**
	 * Clears metadata that must be re-entered for a newly selected exam PDF.
	 */
	void clearForNewPdf() {
		providerField.getSelectionModel().clearSelection();
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.getEditor().clear();
		bookletField.getSelectionModel().clearSelection();
		bookletField.getEditor().clear();
		booklet = null;
	}

	/**
	 * Returns the persisted booklet currently used for question regions.
	 *
	 * @return the current booklet, or {@code null} before exam metadata is set
	 */
	ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * Invalidates the active exam when classification moves to a different subject.
	 *
	 * @param subject the newly selected classification subject, or {@code null}
	 */
	void invalidateForSubjectChange(Subject subject) {
		if (booklet == null) {
			return;
		}
		Subject examSubject = booklet.getExam().getSubject();
		if (subject == null || examSubject.getId() != subject.getId()) {
			booklet = null;
			selectionCursorHandler.accept(false);
		}
	}

	/**
	 * Applies a validated PDF selection and clears metadata from the old PDF.
	 *
	 * @param selectedPdf the selected exam PDF
	 */
	void selectExamPdf(SelectedPdf selectedPdf) {
		if (selectedPdf == null) {
			throw new NullPointerException("selectedPdf");
		}
		examPdfHandler.accept(selectedPdf);
		currentPdfPath = selectedPdf.path();
		clearForNewPdf();
		selectedPdfLabel.setText(selectedPdf.file().getName());
	}
}