package au.edu.eq.questionbank.ui;

import java.nio.file.Path;
import java.time.Year;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * Collects exam and booklet metadata and associates it with a selected source
 * PDF.
 */
final class ExamMetadataPane extends HBox {

	private record ExamMetadataInput(Subject subject, String providerName, Integer year, String assessmentName,
			String bookletName) {
	}

	private static final double CONTROL_SPACING = 8.0;
	private static final double PROVIDER_FIELD_WIDTH = 100.0;
	private static final double YEAR_FIELD_WIDTH = 70.0;
	private static final double ASSESSMENT_FIELD_WIDTH = 180.0;
	private static final double BOOKLET_FIELD_WIDTH = 140.0;
	private static final int YEAR_LOOKBACK_YEARS = 15;
	private static final Insets BAR_PADDING = new Insets(6, 10, 6, 10);
	private static final Insets PANEL_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String INPUT_GROUP_STYLE = BORDER_STYLE + "-fx-padding: 5;";
	private static final String HEADING_STYLE = "-fx-font-weight: bold;";

	private final ComboBox<String> assessmentField = new ComboBox<>();
	private final ComboBox<String> bookletField = new ComboBox<>();
	private final ComboBox<String> providerField = new ComboBox<>();
	private final ComboBox<Integer> yearField = new ComboBox<>();
	private final Button choosePdfButton = new Button("Choose PDF...");
	private final Button setExamButton = new Button("Set Exam");
	private final Label examSubjectLabel = new Label("Not selected");
	private final Label selectedPdfLabel = new Label("No PDF selected");

	private final CurriculumSelectionModel curriculumSelectionModel;
	private final ExamMetadataOptionsRepository optionsRepository;
	private final PdfFilePicker pdfFilePicker;
	private final Path pdfDataRoot;
	private final BooleanSupplier examPdfAvailable;
	private final Consumer<SelectedPdf> examPdfHandler;
	private final Consumer<Boolean> selectionCursorHandler;

	private Path currentPdfPath;
	private ExamBooklet booklet;

	ExamMetadataPane(Stage stage, Path pdfDataRoot, CurriculumSelectionModel curriculumSelectionModel,
			ExamMetadataOptionsRepository optionsRepository, BooleanSupplier examPdfAvailable,
			Consumer<SelectedPdf> examPdfHandler, Consumer<Boolean> selectionCursorHandler) {
		this.pdfDataRoot = Objects.requireNonNull(pdfDataRoot, "pdfDataRoot").toAbsolutePath().normalize();
		this.curriculumSelectionModel = Objects.requireNonNull(curriculumSelectionModel, "curriculumSelectionModel");
		this.optionsRepository = Objects.requireNonNull(optionsRepository, "optionsRepository");
		this.examPdfAvailable = Objects.requireNonNull(examPdfAvailable, "examPdfAvailable");
		this.examPdfHandler = Objects.requireNonNull(examPdfHandler, "examPdfHandler");
		this.selectionCursorHandler = Objects.requireNonNull(selectionCursorHandler, "selectionCursorHandler");
		pdfFilePicker = new PdfFilePicker(this.pdfDataRoot);

		configureFields();
		configureActions(stage);
		loadOptions();
		getChildren().add(createDetailsGroup());
		setAlignment(Pos.CENTER);
		setPadding(BAR_PADDING);
	}

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

	ExamBooklet getBooklet() {
		return booklet;
	}

	void selectExamPdf(SelectedPdf selectedPdf) {
		Objects.requireNonNull(selectedPdf, "selectedPdf");
		examPdfHandler.accept(selectedPdf);
		currentPdfPath = selectedPdf.path();
		clearForNewPdf();
		selectedPdfLabel.setText(selectedPdf.file().getName());
	}

	void setSelectedSubject(Subject subject) {
		examSubjectLabel.setText(subject == null ? "Not selected" : subject.getName());
	}

	private void applyInputToControls(ExamMetadataInput input) {
		providerField.setValue(input.providerName());
		assessmentField.setValue(input.assessmentName());
		bookletField.setValue(input.bookletName());
	}

	private void chooseExamPdf(Stage stage) {
		SelectedPdf selectedPdf = pdfFilePicker.choose(stage, "Choose exam PDF",
				"PDF must be inside the configured PDF data folder.");
		if (selectedPdf == null) {
			return;
		}

		selectExamPdf(selectedPdf);
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
		setExamButton.setId("set-exam");
	}

	private ExamBooklet createExamBooklet(ExamMetadataInput input) {
		ExamProvider provider = new ExamProvider(1, input.providerName());
		Exam exam = new Exam(1, input.subject(), provider, input.year(), input.assessmentName());
		SourceDocument sourceDocument = new SourceDocument(1, pdfDataRoot.relativize(currentPdfPath).toString());
		return new ExamBooklet(1, exam, input.bookletName(), sourceDocument);
	}

	private HBox createDetailsGroup() {
		HBox group = new HBox(CONTROL_SPACING, createSectionLabel("Exam Details:"), createPdfDetails(),
				createMetadataDetails());
		group.setAlignment(Pos.CENTER_LEFT);
		configureBorderedPanel(group);
		return group;
	}

	private HBox createMetadataDetails() {
		HBox details = new HBox(CONTROL_SPACING, new Label("Subject"), examSubjectLabel, new Label("Provider"),
				providerField, new Label("Year"), yearField, new Label("Assessment"), assessmentField,
				new Label("Booklet"), bookletField, setExamButton);
		details.setAlignment(Pos.CENTER_LEFT);
		details.setStyle(INPUT_GROUP_STYLE);
		return details;
	}

	private HBox createPdfDetails() {
		HBox details = new HBox(CONTROL_SPACING, choosePdfButton, selectedPdfLabel);
		details.setAlignment(Pos.CENTER_LEFT);
		details.setStyle(INPUT_GROUP_STYLE);
		return details;
	}

	private Label createSectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle(HEADING_STYLE);
		return label;
	}

	private void configureBorderedPanel(Region panel) {
		panel.setPadding(PANEL_PADDING);
		panel.setStyle(BORDER_STYLE);
	}

	private String findPrerequisiteError() {
		if (!examPdfAvailable.getAsBoolean()) {
			return "Choose a PDF first.";
		}
		if (curriculumSelectionModel.getSubject() == null) {
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
		return new ExamMetadataInput(curriculumSelectionModel.getSubject(), providerField.getEditor().getText().trim(),
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

		booklet = createExamBooklet(input);
		rememberOptions(input);
		applyInputToControls(input);
		selectionCursorHandler.accept(true);
	}

	private void showError(String message) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setHeaderText("Exam details are incomplete.");
		alert.setContentText(message);
		alert.showAndWait();
	}
}
