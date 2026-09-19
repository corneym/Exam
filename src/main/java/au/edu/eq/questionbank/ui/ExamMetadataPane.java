package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Year;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Collects exam and booklet metadata and associates it with a selected source
 * PDF.
 */
final class ExamMetadataPane extends VBox {

	private static final double SUBJECT_FIELD_WIDTH = 140.0;
	private static final int FORM_GAP = 12;
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
	private final Label selectedPdfLabel = new Label("No PDF selected");
	private final SqliteExamImporter examImporter;
	private final SqliteExamWriter examWriter;
	private final CurriculumSelectionModel curriculumSelectionModel;
	private final ExamMetadataOptionsRepository optionsRepository;
	private final PdfFilePicker pdfFilePicker;
	private final Path pdfDataRoot;
	private final BooleanSupplier examChangeAllowed;
	private final PdfStore pdfStore;
	private final Consumer<SelectedPdf> examPdfHandler;
	private final Consumer<Boolean> selectionCursorHandler;
	private final Consumer<Subject> examSubjectHandler;
	private Path currentPdfPath;
	private Path pendingPdfPath;
	private ExamBooklet booklet;
	private ExamBooklet pendingKnownBooklet;

	/**
	 * Creates the exam metadata workflow controls and persistence integration.
	 */
	ExamMetadataPane(Stage stage, Path pdfDataRoot, CurriculumSelectionModel curriculumSelectionModel,
			ExamMetadataOptionsRepository optionsRepository, SqliteExamImporter examImporter,
			SqliteExamWriter examWriter, BooleanSupplier examChangeAllowed, Consumer<SelectedPdf> examPdfHandler,
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
		if (examChangeAllowed == null) {
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
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		this.pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.optionsRepository = optionsRepository;
		this.examChangeAllowed = examChangeAllowed;
		this.examPdfHandler = examPdfHandler;
		this.examImporter = examImporter;
		this.examWriter = examWriter;
		this.selectionCursorHandler = selectionCursorHandler;
		this.examSubjectHandler = examSubjectHandler;
		pdfFilePicker = new PdfFilePicker(this.pdfDataRoot);
		pdfStore = new PdfStore(this.pdfDataRoot);
		configureFields();
		configureActions(stage);
		loadOptions();
		getChildren().add(createDetailsGrid());
		setSpacing(CONTROL_SPACING);
		setPadding(PANEL_PADDING);
	}

	/**
	 * Activates an already-persisted exam booklet for question capture without
	 * importing or creating any database records.
	 *
	 * @param booklet the existing booklet to activate
	 * @param pdfPath the resolved path of the booklet's stored PDF
	 */
	void activateExistingBooklet(ExamBooklet booklet, Path pdfPath) {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (pdfPath == null) {
			throw new NullPointerException("pdfPath");
		}
		this.booklet = booklet;
		currentPdfPath = pdfPath.toAbsolutePath().normalize();
		applyBookletMetadataToControls(booklet);
		selectedPdfLabel.setText(currentPdfPath.getFileName().toString());
		selectionCursorHandler.accept(true);
		examSubjectHandler.accept(booklet.getExam().getSubject());
	}

	void beginImport() {
		pendingPdfPath = null;
		pendingKnownBooklet = null;
		selectedPdfLabel.setText("No PDF selected");

		// A new open/import attempt must allow metadata entry unless the selected
		// PDF is subsequently recognised as an existing persisted booklet.
		setKnownPdfMetadataMode(false);
		providerField.getSelectionModel().clearSelection();
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.getEditor().clear();
		bookletField.getSelectionModel().clearSelection();
		bookletField.getEditor().clear();
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

	boolean confirmDetails() {
		String prerequisiteError = findPrerequisiteError();
		if (prerequisiteError != null) {
			showError(prerequisiteError);
			return false;
		}

		// A recognised source already owns authoritative Exam/Booklet metadata. Reopen
		// that persisted entity instead of passing it through the import/create path.
		if (pendingKnownBooklet != null) {
			return confirmKnownBooklet();
		}
		ExamMetadataInput input = readInput();
		if (!isComplete(input)) {
			showError("Complete all exam details.");
			return false;
		}
		if (!examChangeAllowed.getAsBoolean()) {
			return false;
		}
		try {
			Path storedPath = pdfStore.importExamPdf(pendingPdfPath, input.subject().getName(), input.providerName(),
					input.year());
			ExamBooklet importedBooklet = createExamBooklet(input, storedPath);
			booklet = importedBooklet;
			currentPdfPath = storedPath;
			pendingPdfPath = null;
			rememberOptions(input);
			applyInputToControls(input);
			selectedPdfLabel.setText(currentPdfPath.getFileName().toString());
			examSubjectHandler.accept(input.subject());
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, pdfDataRoot);
			examPdfHandler.accept(selectedPdf);
			selectionCursorHandler.accept(true);
			return true;
		} catch (IOException e) {
			showFileError(e.getMessage());
			return false;
		} catch (SQLException e) {
			showDatabaseError(e.getMessage());
			return false;
		} catch (IllegalArgumentException e) {
			showFileError(e.getMessage());
			return false;
		}
	}

	/**
	 * Corrects an existing Exam and refreshes the active capture metadata when that
	 * Exam is currently open.
	 *
	 * @param exam           persisted Exam being corrected
	 * @param providerName   corrected provider name
	 * @param year           corrected assessment year
	 * @param assessmentName corrected assessment name
	 * @return corrected Exam with the same persistent identity
	 * @throws SQLException if the correction cannot be persisted
	 */
	/**
	 * Corrects an existing Exam and refreshes the active capture metadata when that
	 * Exam is currently open.
	 *
	 * @param exam           persisted Exam being corrected
	 * @param providerName   corrected provider name
	 * @param year           corrected assessment year
	 * @param assessmentName corrected assessment name
	 * @return corrected Exam with the same persistent identity
	 * @throws SQLException if the correction cannot be persisted
	 */
	Exam correctExamMetadata(Exam exam, String providerName, int year, String assessmentName) throws SQLException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		String previousProviderName = exam.getProvider().getName();
		Exam corrected = examWriter.correctExamMetadata(exam, providerName, year, assessmentName);

		// Remove a corrected typo from the reusable Provider suggestions only when
		// persistence confirms that no remaining Exam still uses that provider.
		if (!previousProviderName.equals(corrected.getProvider().getName())
				&& !examWriter.examProviderExists(previousProviderName)) {
			optionsRepository.removeProvider(previousProviderName);
		}
		optionsRepository.addProvider(corrected.getProvider().getName());
		optionsRepository.addAssessment(corrected.getName());

		// Rebuild the suggestion lists immediately so the Import Exam controls do
		// not continue showing a provider label that has just been corrected.
		loadOptions();

		// If this Exam is currently active, replace only the in-memory Exam object.
		// Booklet and source-document identities remain unchanged.
		if (booklet != null && booklet.getExam().getId() == corrected.getId()) {
			booklet = new ExamBooklet(booklet.getId(), corrected, booklet.getName(), booklet.getSourceDocument());
			providerField.setValue(corrected.getProvider().getName());
			yearField.setValue(corrected.getYear());
			assessmentField.setValue(corrected.getName());
		}
		return corrected;
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

	void refreshSubjects() {
		Subject selectedSubject = subjectField.getValue();
		subjectField.getItems().setAll(curriculumSelectionModel.getSubjects());
		if (selectedSubject != null) {
			for (Subject subject : subjectField.getItems()) {
				if (subject.getId() == selectedSubject.getId()) {
					subjectField.setValue(subject);
					return;
				}
			}
		}
		Subject currentSubject = curriculumSelectionModel.getSubject();
		if (currentSubject != null) {
			for (Subject subject : subjectField.getItems()) {
				if (subject.getId() == currentSubject.getId()) {
					subjectField.setValue(subject);
					return;
				}
			}
		}
		subjectField.setValue(null);
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

	void stageExamPdf(Path sourcePath) {
		if (sourcePath == null) {
			throw new NullPointerException("sourcePath");
		}
		Path normalizedSource = sourcePath.toAbsolutePath().normalize();
		pendingPdfPath = normalizedSource;
		pendingKnownBooklet = null;
		selectedPdfLabel.setText(normalizedSource.getFileName().toString());

		// Remove metadata belonging to a previously staged PDF before deciding
		// whether this source is a persisted booklet or a genuinely new import.
		clearPendingImportMetadata();
		setKnownPdfMetadataMode(false);
		try {
			ExamBooklet knownBooklet = findKnownBooklet(normalizedSource);
			if (knownBooklet == null) {
				return;
			}
			pendingKnownBooklet = knownBooklet;
			applyBookletMetadataToControls(knownBooklet);

			// Persisted metadata is authoritative here. Corrections belong in the
			// explicit Edit Exam workflow rather than creating another Exam while
			// reopening an existing PDF.
			setKnownPdfMetadataMode(true);
		} catch (SQLException exception) {
			pendingPdfPath = null;
			pendingKnownBooklet = null;
			selectedPdfLabel.setText("No PDF selected");
			showDatabaseError(exception.getMessage());
		} catch (IOException exception) {
			pendingPdfPath = null;
			pendingKnownBooklet = null;
			selectedPdfLabel.setText("No PDF selected");
			showFileError(exception.getMessage());
		} catch (IllegalStateException exception) {
			pendingPdfPath = null;
			pendingKnownBooklet = null;
			selectedPdfLabel.setText("No PDF selected");
			showDatabaseError(exception.getMessage());
		}
	}

	private void applyBookletMetadataToControls(ExamBooklet existingBooklet) {
		Exam exam = existingBooklet.getExam();
		subjectField.setValue(exam.getSubject());
		providerField.setValue(exam.getProvider().getName());
		yearField.setValue(exam.getYear());
		assessmentField.setValue(exam.getName());
		bookletField.setValue(existingBooklet.getName());
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
		stageExamPdf(selectedPath);
	}

	private void clearPendingImportMetadata() {
		providerField.getSelectionModel().clearSelection();
		providerField.getEditor().clear();
		yearField.getSelectionModel().clearSelection();
		assessmentField.getSelectionModel().clearSelection();
		assessmentField.getEditor().clear();
		bookletField.getSelectionModel().clearSelection();
		bookletField.getEditor().clear();
	}

	private void configureActions(Stage stage) {
		choosePdfButton.setOnAction(_ -> chooseExamPdf(stage));
		choosePdfButton.setTooltip(new Tooltip("Choose the PDF containing the exam booklet."));
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
		subjectField.setPrefWidth(SUBJECT_FIELD_WIDTH);
		subjectField.getItems().setAll(curriculumSelectionModel.getSubjects());
		Subject currentSubject = curriculumSelectionModel.getSubject();
		if (currentSubject != null) {
			subjectField.setValue(currentSubject);
		}
		selectedPdfLabel.setWrapText(true);
	}

	private boolean confirmKnownBooklet() {
		if (!examChangeAllowed.getAsBoolean()) {
			return false;
		}
		ExamBooklet existingBooklet = pendingKnownBooklet;
		Path storedPath;
		try {

			// Always reopen the authoritative managed PDF, even when later recognition
			// is extended to external byte-identical copies.
			storedPath = pdfStore.resolve(existingBooklet.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException exception) {
			showFileError(exception.getMessage());
			return false;
		}
		if (!Files.isRegularFile(storedPath)) {
			showFileError("Stored exam PDF is unavailable: " + storedPath);
			return false;
		}
		try {
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, pdfDataRoot);

			// Open the authoritative persisted document first. Only after that succeeds
			// should the in-memory active booklet be changed.
			examPdfHandler.accept(selectedPdf);
			activateExistingBooklet(existingBooklet, storedPath);
			pendingPdfPath = null;
			pendingKnownBooklet = null;
			return true;
		} catch (RuntimeException exception) {
			showFileError(exception.getMessage());
			return false;
		}
	}

	private GridPane createDetailsGrid() {
		GridPane grid = new GridPane();
		grid.setHgap(FORM_GAP);
		grid.setVgap(FORM_GAP);
		grid.setStyle(BORDER_STYLE);
		grid.add(createFieldLabel("Subject:"), 0, 0);
		grid.add(subjectField, 1, 0);
		grid.add(createFieldLabel("PDF:"), 0, 1);
		grid.add(createPdfControls(), 1, 1);
		grid.add(createFieldLabel("Provider:"), 0, 2);
		grid.add(providerField, 1, 2);
		grid.add(createFieldLabel("Year:"), 0, 3);
		grid.add(yearField, 1, 3);
		grid.add(createFieldLabel("Assessment:"), 0, 4);
		grid.add(assessmentField, 1, 4);
		grid.add(createFieldLabel("Booklet:"), 0, 5);
		grid.add(bookletField, 1, 5);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		GridPane.setHgrow(bookletField, Priority.ALWAYS);
		return grid;
	}

	private ExamBooklet createExamBooklet(ExamMetadataInput input, Path storedPath) throws SQLException {
		String relativePath = pdfDataRoot.relativize(storedPath).toString();
		return examImporter.importExam(input.subject(), input.providerName(), input.year(), input.assessmentName(),
				input.bookletName(), relativePath);
	}

	private Label createFieldLabel(String text) {
		Label label = new Label(text);

		// Form labels must retain enough width to display their complete text.
		label.setMinWidth(Region.USE_PREF_SIZE);
		return label;
	}

	private HBox createPdfControls() {
		HBox controls = new HBox(CONTROL_SPACING, choosePdfButton, selectedPdfLabel);
		controls.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(selectedPdfLabel, Priority.ALWAYS);
		return controls;
	}

	private ExamBooklet findKnownBooklet(Path sourcePath) throws SQLException, IOException {

		// A persisted managed path is authoritative. Use it before considering
		// content identity so duplicate bytes elsewhere cannot make an exact path
		// relationship ambiguous.
		ExamBooklet managedMatch = findKnownManagedBooklet(sourcePath);
		if (managedMatch != null) {
			return managedMatch;
		}
		if (!Files.isRegularFile(sourcePath)) {
			throw new IOException("Exam PDF source is not a regular file: " + sourcePath);
		}
		long sourceSize = Files.size(sourcePath);
		ExamBooklet byteMatch = null;
		for (ExamBooklet candidate : examWriter.findAllExamBooklets()) {
			Path storedPath;
			try {
				storedPath = pdfStore.resolve(candidate.getSourceDocument().getRelativePath());
			} catch (IllegalArgumentException exception) {

				// An invalid persisted path cannot establish identity with the selected
				// external file. Leave correction of that stored path to data repair.
				continue;
			}
			if (!Files.isRegularFile(storedPath)) {

				// A missing stored PDF cannot be compared safely, so it cannot establish
				// content identity with this selection.
				continue;
			}
			if (Files.size(storedPath) != sourceSize) {

				// Different byte lengths cannot represent the same PDF. This inexpensive
				// filter avoids unnecessary full-file comparisons.
				continue;
			}
			boolean identical = Files.isSameFile(sourcePath, storedPath)
					|| Files.mismatch(sourcePath, storedPath) == -1;
			if (!identical) {
				continue;
			}
			if (byteMatch != null && byteMatch.getId() != candidate.getId()) {

				// Two persisted booklets with identical source bytes make an external
				// copy ambiguous. Do not choose one based on filename or row order.
				throw new IllegalStateException("Selected PDF matches more than one persisted exam booklet.");
			}
			byteMatch = candidate;
		}
		return byteMatch;
	}

	private ExamBooklet findKnownManagedBooklet(Path sourcePath) throws SQLException {

		// A direct persisted-path match is authoritative only for files beneath the
		// configured PDF data root. External copies are handled separately later.
		if (!sourcePath.startsWith(pdfDataRoot)) {
			return null;
		}
		String relativePath = pdfDataRoot.relativize(sourcePath).toString();
		return examWriter.findExamBookletBySourceDocumentPath(relativePath);
	}

	private String findPrerequisiteError() {
		if (pendingPdfPath == null) {
			return "Choose a PDF first.";
		}
		if (subjectField.getValue() == null) {
			return "Select a subject before confirming the exam.";
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

	private void setKnownPdfMetadataMode(boolean knownPdf) {

		// When persistence already identifies the selected document, these values
		// describe the existing Exam/Booklet relationship and must not be edited as
		// though a new hierarchy were being imported.
		subjectField.setDisable(knownPdf);
		providerField.setDisable(knownPdf);
		yearField.setDisable(knownPdf);
		assessmentField.setDisable(knownPdf);
		bookletField.setDisable(knownPdf);
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

	private record ExamMetadataInput(Subject subject, String providerName, Integer year, String assessmentName,
			String bookletName) {
	}
}
