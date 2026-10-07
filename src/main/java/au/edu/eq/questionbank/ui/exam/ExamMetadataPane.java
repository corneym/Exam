package au.edu.eq.questionbank.ui.exam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Year;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.ExamMetadataCorrectionService;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.service.document.SourceDocumentHashService;
import au.edu.eq.questionbank.ui.model.CurriculumSelectionModel;
import au.edu.eq.questionbank.ui.pdf.PdfFilePicker;
import au.edu.eq.questionbank.ui.pdf.SelectedPdf;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
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
public final class ExamMetadataPane extends VBox {

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
	private static final double QUESTION_FORMAT_FIELD_WIDTH = 180.0;
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
	private final ExamMetadataCorrectionService examMetadataCorrectionService;
	private final ComboBox<ExamBookletQuestionFormat> questionFormatField = new ComboBox<>();
	private final SourceDocumentHashService sourceDocumentHashService = new SourceDocumentHashService();
	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates the exam metadata workflow controls and persistence integration.
	 *
	 * @param stage                         owner used by PDF selection
	 * @param managedDataLayout             canonical application managed-data
	 *                                      layout
	 * @param pdfDataRoot                   legacy root containing managed Exam PDFs
	 * @param curriculumSelectionModel      shared Subject and classification state
	 * @param optionsRepository             stored provider and assessment options
	 * @param examImporter                  service for importing an Exam PDF
	 * @param examWriter                    writer for persisted Exam metadata
	 * @param examMetadataCorrectionService service for correcting Exam identity and
	 *                                      relocating PDFs
	 * @param examChangeAllowed             guard for changing the active Exam
	 * @param examPdfHandler                callback that opens the selected Exam
	 *                                      PDF
	 * @param selectionCursorHandler        callback controlling PDF region
	 *                                      selection
	 * @param examSubjectHandler            callback receiving the active Exam
	 *                                      Subject
	 */
	public ExamMetadataPane(Stage stage, ManagedDataLayout managedDataLayout, Path pdfDataRoot,
			CurriculumSelectionModel curriculumSelectionModel, ExamMetadataOptionsRepository optionsRepository,
			SqliteExamImporter examImporter, SqliteExamWriter examWriter,
			ExamMetadataCorrectionService examMetadataCorrectionService, BooleanSupplier examChangeAllowed,
			Consumer<SelectedPdf> examPdfHandler, Consumer<Boolean> selectionCursorHandler,
			Consumer<Subject> examSubjectHandler) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
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
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (examMetadataCorrectionService == null) {
			throw new NullPointerException("examMetadataCorrectionService");
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
		this.managedDataLayout = managedDataLayout;
		this.pdfDataRoot = pdfDataRoot.toAbsolutePath().normalize();
		this.curriculumSelectionModel = curriculumSelectionModel;
		this.optionsRepository = optionsRepository;
		this.examChangeAllowed = examChangeAllowed;
		this.examPdfHandler = examPdfHandler;
		this.examImporter = examImporter;
		this.examWriter = examWriter;
		this.examMetadataCorrectionService = examMetadataCorrectionService;
		this.selectionCursorHandler = selectionCursorHandler;
		this.examSubjectHandler = examSubjectHandler;
		pdfFilePicker = new PdfFilePicker(this.pdfDataRoot);

		// New imports use the Subject-first layout while persisted Sprint 13 paths
		// remain readable through the legacy PDF root until migration.
		pdfStore = new PdfStore(this.managedDataLayout, this.pdfDataRoot);
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
	public void activateExistingBooklet(ExamBooklet booklet, Path pdfPath) {
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

	/** Resets the controls for a new PDF open or import attempt. */
	public void beginImport() {
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

		// A genuinely new booklet must receive an explicit format selection.
		clearQuestionFormatField();
	}

	/**
	 * Clears the currently active persisted booklet after that booklet has been
	 * deleted from authoritative storage.
	 */
	public void clearActiveBooklet() {

		// No pending import or managed path may survive the deletion of the active
		// booklet identity.
		pendingPdfPath = null;
		pendingKnownBooklet = null;
		currentPdfPath = null;
		clearForNewPdf();
		selectedPdfLabel.setText("No PDF selected");
		selectionCursorHandler.accept(false);
	}

	/**
	 * Corrects an existing Exam, including relocation of managed booklet and answer
	 * PDFs when provider or year changes the authoritative storage directory.
	 *
	 * @param exam           persisted Exam being corrected
	 * @param providerName   corrected provider name
	 * @param year           corrected assessment year
	 * @param assessmentName corrected assessment name
	 * @return corrected Exam with the same persistent identity
	 * @throws SQLException if persistence fails
	 * @throws IOException  if managed source files cannot be relocated
	 */
	public Exam correctExamMetadata(Exam exam, String providerName, int year, String assessmentName)
			throws SQLException, IOException {
		if (exam == null) {
			throw new NullPointerException("exam");
		}
		String previousProviderName = exam.getProvider().getName();

		// The correction service relocates every booklet/answer source and updates all
		// corresponding SourceDocument paths before this pane changes its in-memory
		// capture state.
		ExamMetadataCorrectionService.Result correction = examMetadataCorrectionService.correct(exam, providerName,
				year, assessmentName);
		Exam corrected = correction.exam();
		if (!previousProviderName.equals(corrected.getProvider().getName())
				&& !examWriter.examProviderExists(previousProviderName)) {
			optionsRepository.removeProvider(previousProviderName);
		}
		optionsRepository.addProvider(corrected.getProvider().getName());
		optionsRepository.addAssessment(corrected.getName());
		loadOptions();
		if (booklet != null && booklet.getExam().getId() == corrected.getId()) {
			long sourceDocumentId = booklet.getSourceDocument().getId();
			String correctedRelativePath = correction.sourceDocumentPaths().get(sourceDocumentId);

			// Every booklet source belonging to the corrected Exam was included in the
			// relocation plan. Missing it here would leave the active capture object stale.
			if (correctedRelativePath == null) {
				throw new IllegalStateException(
						"Corrected Exam did not return the active booklet's SourceDocument path");
			}

			// Relocating a managed file changes its path, not its byte identity.
			SourceDocument correctedSourceDocument = new SourceDocument(sourceDocumentId, correctedRelativePath,
					booklet.getSourceDocument().getContentSha256());

			// Exam correction changes Exam metadata only. Preserve the booklet's separate
			// persisted Question-format classification in the refreshed in-memory object.
			// Exam correction relocates the source but does not alter the booklet's
			// structural planning metadata.
			booklet = new ExamBooklet(booklet.getId(), corrected, booklet.getName(), correctedSourceDocument,
					booklet.getQuestionFormat(), booklet.getExpectedQuestionCount());
			currentPdfPath = pdfStore.resolve(correctedRelativePath);
			providerField.setValue(corrected.getProvider().getName());
			yearField.setValue(corrected.getYear());
			assessmentField.setValue(corrected.getName());
			selectedPdfLabel.setText(currentPdfPath.getFileName().toString());
		}
		return corrected;
	}

	/**
	 * Returns the persisted booklet currently used for question regions.
	 *
	 * @return the current booklet, or {@code null} before exam metadata is set
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * Invalidates the active exam when classification moves to a different subject.
	 *
	 * @param subject the newly selected classification subject, or {@code null}
	 */
	public void invalidateForSubjectChange(Subject subject) {
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
	 * Refreshes the planning metadata carried by the currently active booklet when
	 * that same persisted booklet has been updated elsewhere.
	 *
	 * @param updatedBooklet authoritative updated booklet
	 * @throws NullPointerException     if {@code updatedBooklet} is {@code null}
	 * @throws IllegalArgumentException if the replacement identity contradicts the
	 *                                  active booklet's Exam
	 */
	public void refreshActiveBookletPlanning(ExamBooklet updatedBooklet) {
		if (updatedBooklet == null) {
			throw new NullPointerException("updatedBooklet");
		}
		if (booklet == null || booklet.getId() != updatedBooklet.getId()) {
			return;
		}
		if (booklet.getExam().getId() != updatedBooklet.getExam().getId()) {
			throw new IllegalArgumentException("Updated booklet belongs to a different Exam");
		}

		// Persistence remains authoritative for structural planning while the
		// currently opened PDF and booklet identity remain unchanged.
		booklet = updatedBooklet;
	}

	/** Reloads selectable Subjects while preserving the current selection by id. */
	public void refreshSubjects() {
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
	 * Reopens the active persisted Exam PDF after managed files have been
	 * relocated. Does nothing when no Exam booklet is currently active.
	 */
	public void reopenActiveExamPdf() {
		if (booklet == null || currentPdfPath == null) {
			return;
		}
		if (!Files.isRegularFile(currentPdfPath)) {
			throw new IllegalStateException("The active Exam PDF is unavailable: " + currentPdfPath);
		}

		// currentPdfPath is updated during successful metadata correction, and remains
		// the old authoritative path when correction fails and filesystem rollback
		// runs.
		examPdfHandler.accept(new SelectedPdf(currentPdfPath.toFile(), currentPdfPath, pdfDataRoot));
	}

	/**
	 * Changes the lifecycle state of the Exam owning the active booklet and
	 * refreshes the in-memory booklet around the persisted Exam state.
	 *
	 * @param captureState replacement Exam lifecycle state
	 * @return the persisted Exam carrying the replacement state
	 * @throws SQLException          if persistence fails
	 * @throws NullPointerException  if {@code captureState} is {@code null}
	 * @throws IllegalStateException if no Exam booklet is active
	 */
	public Exam setActiveExamCaptureState(ExamCaptureState captureState) throws SQLException {
		if (captureState == null) {
			throw new NullPointerException("captureState");
		}
		if (booklet == null) {
			throw new IllegalStateException("No Exam booklet is active");
		}
		Exam updatedExam = examWriter.setExamCaptureState(booklet.getExam(), captureState);

		// Keep the active capture object consistent with persisted lifecycle state
		// without altering any booklet-level structure.
		booklet = new ExamBooklet(booklet.getId(), updatedExam, booklet.getName(), booklet.getSourceDocument(),
				booklet.getQuestionFormat(), booklet.getExpectedQuestionCount());
		return updatedExam;
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

		// A newly selected PDF must not inherit the previous booklet's format.
		clearQuestionFormatField();
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
					input.year(), input.assessmentName());
			ExamBooklet importedBooklet = createExamBooklet(input, storedPath);
			booklet = importedBooklet;
			currentPdfPath = storedPath;
			pendingPdfPath = null;
			rememberOptions(input);
			applyInputToControls(input);
			selectedPdfLabel.setText(currentPdfPath.getFileName().toString());
			examSubjectHandler.accept(input.subject());

			// Newly imported files are rooted at the application data root rather than
			// the former dedicated PDF root.
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, managedDataLayout.dataRoot());
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
		if (existingBooklet.getQuestionFormat() == ExamBookletQuestionFormat.UNSPECIFIED) {

			// UNSPECIFIED is migration-only state and must never appear as a
			// user-selectable question format.
			clearQuestionFormatField();
		} else {

			// Existing explicit booklet metadata is authoritative when the PDF is reopened.
			questionFormatField.setValue(existingBooklet.getQuestionFormat());
		}
	}

	private void applyInputToControls(ExamMetadataInput input) {
		providerField.setValue(input.providerName());
		assessmentField.setValue(input.assessmentName());
		bookletField.setValue(input.bookletName());

		// Show the persisted format that was just used to create the booklet.
		questionFormatField.setValue(input.questionFormat());
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

		// Format belongs to the selected booklet, so clear it with the other staged
		// booklet metadata.
		clearQuestionFormatField();
	}

	private void clearQuestionFormatField() {

		// Clear both JavaFX selection representations so a previous booklet format
		// cannot remain as a stale ComboBox value.
		questionFormatField.getSelectionModel().clearSelection();
		questionFormatField.setValue(null);
		questionFormatField.setPromptText("Select format");
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
		questionFormatField.setId("exam-question-format");
		questionFormatField.setPrefWidth(QUESTION_FORMAT_FIELD_WIDTH);
		questionFormatField.setPromptText("Select format");
		questionFormatField.setTooltip(new Tooltip(
				"Sets the booklet format used to infer response types for newly captured Questions; existing Questions are unchanged."));

		// UNSPECIFIED is migration-only metadata. New booklets must use one of the
		// three explicit formats presented to the user.
		questionFormatField.getItems().setAll(ExamBookletQuestionFormat.MULTIPLE_CHOICE,
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, ExamBookletQuestionFormat.MIXED);
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

			// The transitional store resolves both Subject-first and legacy persisted
			// paths without changing their database representation.
			storedPath = pdfStore.resolve(existingBooklet.getSourceDocument().getRelativePath());
		} catch (IllegalArgumentException exception) {
			showFileError(exception.getMessage());
			return false;
		}
		if (!Files.isRegularFile(storedPath)) {
			showFileError("Stored exam PDF is unavailable: " + storedPath);
			return false;
		}
		ExamBooklet resolvedBooklet = resolveKnownBookletQuestionFormat(existingBooklet);
		if (resolvedBooklet == null) {

			// Cancellation keeps the recognised booklet staged so the user may confirm it
			// again or close the Open Exam workflow.
			return false;
		}

		// Retain the newly classified object if PDF activation subsequently fails so a
		// retry does not attempt to classify the same persisted booklet twice.
		pendingKnownBooklet = resolvedBooklet;
		try {
			Path selectedPdfRoot = selectedPdfRoot(resolvedBooklet.getSourceDocument().getRelativePath());
			SelectedPdf selectedPdf = new SelectedPdf(storedPath.toFile(), storedPath, selectedPdfRoot);

			// Format resolution is complete before the capture workspace is reset and the
			// booklet becomes authoritative.
			examPdfHandler.accept(selectedPdf);
			activateExistingBooklet(resolvedBooklet, storedPath);
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

		// Question format belongs to the booklet because separate booklets from one
		// Exam may contain different kinds of Questions.
		grid.add(createFieldLabel("Question format:"), 0, 6);
		grid.add(questionFormatField, 1, 6);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		GridPane.setHgrow(bookletField, Priority.ALWAYS);
		return grid;
	}

	private ExamBooklet createExamBooklet(ExamMetadataInput input, Path storedPath) throws SQLException, IOException {
		String relativePath = pdfStore.relativePath(storedPath);

		// Hash the final managed bytes so persistence describes the authoritative copy
		// rather than merely the external source selected by the user.
		String contentSha256 = sourceDocumentHashService.sha256(storedPath);
		return examImporter.importExam(input.subject(), input.providerName(), input.year(), input.assessmentName(),
				input.bookletName(), relativePath, input.questionFormat(), contentSha256);
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

		// Exact managed-path identity remains authoritative and avoids unnecessary
		// content work.
		ExamBooklet managedMatch = findKnownManagedBooklet(sourcePath);
		if (managedMatch != null) {
			return managedMatch;
		}
		if (!Files.isRegularFile(sourcePath)) {
			throw new IOException("Exam PDF source is not a regular file: " + sourcePath);
		}
		String selectedHash = sourceDocumentHashService.sha256(sourcePath);
		ExamBooklet contentMatch = null;

		// Hashes provide filename-independent identity for documents imported under
		// schema v16.
		for (SourceDocument matchingSource : examWriter.findSourceDocumentsByHash(selectedHash)) {
			ExamBooklet candidate = examWriter.findExamBookletBySourceDocumentPath(matchingSource.getRelativePath());
			if (candidate == null) {

				// A matching SourceDocument may belong only to an AnswerFile.
				continue;
			}
			if (contentMatch != null && contentMatch.getId() != candidate.getId()) {
				throw new IllegalStateException("Selected PDF matches more than one persisted exam booklet.");
			}
			contentMatch = candidate;
		}
		long sourceSize = Files.size(sourcePath);

		// Migrated documents intentionally have NULL hashes. Retain the old byte
		// comparison only as a compatibility path until those rows are re-inspected.
		for (ExamBooklet candidate : examWriter.findAllExamBooklets()) {
			if (candidate.getSourceDocument().getContentSha256() != null) {
				continue;
			}
			Path storedPath;
			try {
				storedPath = pdfStore.resolve(candidate.getSourceDocument().getRelativePath());
			} catch (IllegalArgumentException exception) {

				// Invalid historical paths cannot establish source identity here.
				continue;
			}
			if (!Files.isRegularFile(storedPath) || Files.size(storedPath) != sourceSize) {
				continue;
			}
			boolean identical = Files.isSameFile(sourcePath, storedPath)
					|| Files.mismatch(sourcePath, storedPath) == -1;
			if (!identical) {
				continue;
			}
			if (contentMatch != null && contentMatch.getId() != candidate.getId()) {

				// A hash match plus an identical un-hashed legacy source is still
				// ambiguous and must not be resolved by arbitrary row order.
				throw new IllegalStateException("Selected PDF matches more than one persisted exam booklet.");
			}
			contentMatch = candidate;
		}
		return contentMatch;
	}

	private ExamBooklet findKnownManagedBooklet(Path sourcePath) throws SQLException {
		Path normalizedSource = sourcePath.toAbsolutePath().normalize();

		// New files persist paths relative to the application data root beneath the
		// explicit subjects/ namespace.
		if (normalizedSource.startsWith(managedDataLayout.subjectsRoot())) {
			String relativePath = managedDataLayout.relativePath(normalizedSource);
			return examWriter.findExamBookletBySourceDocumentPath(relativePath);
		}

		// Until migration, existing files retain their PDF-root-relative database path.
		if (!normalizedSource.startsWith(pdfDataRoot)) {
			return null;
		}
		String relativePath = pdfDataRoot.relativize(normalizedSource).toString();
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
				&& !input.bookletName().isBlank() && input.questionFormat() != null;
	}

	private void loadOptions() {
		providerField.getItems().setAll(optionsRepository.getProviders());
		assessmentField.getItems().setAll(optionsRepository.getAssessments());
		bookletField.getItems().setAll(optionsRepository.getBooklets());
	}

	private ExamMetadataInput readInput() {
		return new ExamMetadataInput(subjectField.getValue(), providerField.getEditor().getText().trim(),
				yearField.getValue(), assessmentField.getEditor().getText().trim(),
				bookletField.getEditor().getText().trim(), questionFormatField.getValue());
	}

	private void rememberOptions(ExamMetadataInput input) {
		optionsRepository.addProvider(input.providerName());
		optionsRepository.addAssessment(input.assessmentName());
		optionsRepository.addBooklet(input.bookletName());
		loadOptions();
	}

	/**
	 * Resolves missing booklet-format metadata before an existing booklet becomes
	 * active in the capture workspace.
	 *
	 * @param existingBooklet persisted booklet being opened
	 * @return the booklet with an explicit format, or {@code null} when the user
	 *         cancels or persistence fails
	 */
	private ExamBooklet resolveKnownBookletQuestionFormat(ExamBooklet existingBooklet) {
		if (existingBooklet == null) {
			throw new NullPointerException("existingBooklet");
		}
		if (existingBooklet.getQuestionFormat() != ExamBookletQuestionFormat.UNSPECIFIED) {

			// Modern booklets already contain authoritative format metadata.
			return existingBooklet;
		}
		ChoiceDialog<ExamBookletQuestionFormat> dialog = new ChoiceDialog<>(ExamBookletQuestionFormat.MIXED,
				ExamBookletQuestionFormat.MULTIPLE_CHOICE, ExamBookletQuestionFormat.WRITTEN_RESPONSE,
				ExamBookletQuestionFormat.MIXED);

		// Legacy booklets are classified when they are explicitly opened for capture,
		// before any new Question entry can begin.
		if (getScene() != null && getScene().getWindow() != null) {
			dialog.initOwner(getScene().getWindow());
		}
		dialog.setTitle("Question Format");
		dialog.setHeaderText("Question format has not been recorded for this booklet.");
		dialog.setContentText("Question format:");
		ExamBookletQuestionFormat selectedFormat = dialog.showAndWait().orElse(null);
		if (selectedFormat == null) {

			// Cancelling leaves both persistence and the active capture booklet unchanged.
			return null;
		}
		try {

			// Persist the booklet-level classification once. Existing Questions retain
			// their own persisted response types.
			return examWriter.classifyLegacyBookletQuestionFormat(existingBooklet, selectedFormat);
		} catch (SQLException | RuntimeException exception) {
			showDatabaseError(exception.getMessage());
			return null;
		}
	}

	private Path selectedPdfRoot(String relativePath) {
		String portablePath = relativePath.replace('\\', '/');

		// SelectedPdf must derive any relative path from the same root that owns the
		// persisted SourceDocument representation.
		if (portablePath.startsWith("subjects/")) {
			return managedDataLayout.dataRoot();
		}
		return pdfDataRoot;
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

		// Question format is also persisted booklet metadata. Reopening a known
		// booklet must not silently permit it to be reclassified.
		questionFormatField.setDisable(knownPdf);
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
			String bookletName, ExamBookletQuestionFormat questionFormat) {
	}
}
