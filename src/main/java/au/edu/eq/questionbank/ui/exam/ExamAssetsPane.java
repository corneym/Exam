package au.edu.eq.questionbank.ui.exam;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Year;
import java.util.List;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.ExamMetadataOptionsRepository;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Presents the main-window Exam/Assets workspace for the current Working
 * Subject.
 * <p>
 * This first workspace increment is intentionally read-only. It establishes the
 * authoritative persisted Exam selection and asset presentation before later
 * increments add editing and asset-management actions.
 */
public final class ExamAssetsPane extends VBox {

	private static final double SPACING = 8.0;
	private static final double ROW_SPACING = 4.0;
	private static final Insets SECTION_PADDING = new Insets(8);
	private static final String BORDER_STYLE = "-fx-border-color: #b0b0b0;-fx-border-width: 1;-fx-border-radius: 3;";
	private static final String HEADING_STYLE = "-fx-font-weight: bold;";
	private static final int YEAR_LOOKBACK_YEARS = 15;
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final ExamMetadataOptionsRepository optionsRepository;
	private final ExamCorrectionHandler correctionHandler;
	private final ComboBox<Exam> examBox = new ComboBox<>();
	private final Label stateLabel = new Label("State: —");
	private final ComboBox<String> providerField = new ComboBox<>();
	private final ComboBox<Integer> yearField = new ComboBox<>();
	private final ComboBox<String> assessmentField = new ComboBox<>();
	private final VBox questionBookletsBox = new VBox(ROW_SPACING);
	private final VBox answerBookletsBox = new VBox(ROW_SPACING);
	private final Button editExamButton = new Button("Edit");
	private final Button cancelButton = new Button("Cancel");
	private final Button saveButton = new Button("Save");
	private boolean editingExamDetails;

	/**
	 * Creates the Exam/Assets workspace.
	 *
	 * @param examWriter        authoritative Exam/booklet persistence
	 * @param answerWriter      authoritative AnswerFile persistence
	 * @param optionsRepository reusable Exam metadata labels
	 * @param correctionHandler authoritative Exam metadata correction
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ExamAssetsPane(SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			ExamMetadataOptionsRepository optionsRepository, ExamCorrectionHandler correctionHandler) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (optionsRepository == null) {
			throw new NullPointerException("optionsRepository");
		}
		if (correctionHandler == null) {
			throw new NullPointerException("correctionHandler");
		}
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
		this.optionsRepository = optionsRepository;
		this.correctionHandler = correctionHandler;

		// Exam/Assets is now a persistent working mode. Leaving it for Capture will be
		// an explicit booklet-selection operation rather than a side effect of Cancel.
		configureControls();
		buildContent();
		setId("exam-assets-workspace");
		setSpacing(SPACING);
	}

	/**
	 * Reloads the persisted Exams belonging to the current Working Subject.
	 *
	 * @param subject authoritative application Subject, or {@code null}
	 * @throws SQLException if Exam persistence cannot be read
	 */
	public void refresh(Subject subject) throws SQLException {
		Long previousExamId = examBox.getValue() == null ? null : Long.valueOf(examBox.getValue().getId());
		examBox.getSelectionModel().clearSelection();
		examBox.getItems().clear();
		clearSelectedExam();

		// Suggestions may have changed elsewhere, but reload them while no Exam value
		// is staged so they cannot erase persisted Provider or Assessment values.
		refreshMetadataOptions();
		if (subject == null) {
			return;
		}
		List<Exam> exams = examWriter.findExamsForSubject(subject);
		examBox.getItems().setAll(exams);
		if (exams.isEmpty()) {
			return;
		}
		Exam selectedExam = null;
		if (previousExamId != null) {

			// Preserve the selected persisted Exam whenever it still belongs to the
			// authoritative Working Subject.
			selectedExam = exams.stream().filter(exam -> exam.getId() == previousExamId.longValue()).findFirst()
					.orElse(null);
		}
		if (selectedExam == null) {

			// Repository ordering already presents the newest Exam first.
			selectedExam = exams.getFirst();
		}
		examBox.getSelectionModel().select(selectedExam);
	}

	private void beginExamDetailsEdit() {
		Exam selectedExam = examBox.getValue();
		if (selectedExam == null) {
			return;
		}

		// Reassert the persisted values directly into the editable ComboBox editors.
		// This prevents suggestion-list state from clearing the metadata when Edit is
		// entered.
		providerField.setValue(selectedExam.getProvider().getName());
		providerField.getEditor().setText(selectedExam.getProvider().getName());
		yearField.setValue(selectedExam.getYear());
		assessmentField.setValue(selectedExam.getName());
		assessmentField.getEditor().setText(selectedExam.getName());
		setExamDetailsEditing(true);
	}

	private void buildContent() {
		Label examHeading = new Label("EXAM");
		examHeading.setStyle(HEADING_STYLE);
		GridPane examSelectorGrid = new GridPane();
		examSelectorGrid.setHgap(SPACING);
		examSelectorGrid.add(examBox, 0, 0);
		examSelectorGrid.add(stateLabel, 1, 0);
		GridPane.setHgrow(examBox, Priority.ALWAYS);
		VBox examDetails = createExamDetailsSection();
		VBox questionBooklets = createSection("QUESTION BOOKLETS", questionBookletsBox);
		VBox answerBooklets = createSection("ANSWER BOOKLETS", answerBookletsBox);
		Region actionSpacer = new Region();
		HBox.setHgrow(actionSpacer, Priority.ALWAYS);

		// Save now commits the active Exam Details edit. Later Slice 1 increments can
		// extend the same workspace action row to other staged asset changes.
		HBox actionRow = new HBox(SPACING, actionSpacer, cancelButton, saveButton);
		getChildren().addAll(examHeading, examSelectorGrid, examDetails, questionBooklets, answerBooklets,
				new Separator(), actionRow);
	}

	private void cancelExamDetailsEdit() {
		Exam selectedExam = examBox.getValue();
		if (!editingExamDetails || selectedExam == null) {
			return;
		}

		// Discard staged editor values by reloading the selected persisted Exam. This
		// deliberately stays inside Exam/Assets mode.
		loadSelectedExamSafely(selectedExam);
	}

	private void clearSelectedExam() {
		stateLabel.setText("State: —");

		providerField.getSelectionModel().clearSelection();
		providerField.setValue(null);
		providerField.getEditor().clear();

		yearField.getSelectionModel().clearSelection();
		yearField.setValue(null);

		assessmentField.getSelectionModel().clearSelection();
		assessmentField.setValue(null);
		assessmentField.getEditor().clear();

		// Never retain asset rows belonging to a previously selected Exam.
		questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));

		setExamDetailsEditing(false);
	}

	private void configureControls() {
		examBox.setId("exam-assets-exam");
		examBox.setMaxWidth(Double.MAX_VALUE);
		examBox.setConverter(new StringConverter<>() {

			@Override
			public Exam fromString(String text) {

				// Existing Exam selection is always persistence-backed.
				return null;
			}

			@Override
			public String toString(Exam exam) {
				return exam == null ? "" : formatExam(exam);
			}
		});
		examBox.valueProperty().addListener((_, _, exam) -> loadSelectedExamSafely(exam));
		stateLabel.setId("exam-assets-state");

		// Provider and Assessment use reusable suggestions but their persisted Exam
		// values remain authoritative.
		providerField.setId("exam-assets-provider");
		providerField.setEditable(true);
		providerField.setMaxWidth(Double.MAX_VALUE);
		assessmentField.setId("exam-assets-assessment");
		assessmentField.setEditable(true);
		assessmentField.setMaxWidth(Double.MAX_VALUE);
		yearField.setId("exam-assets-year");
		yearField.setMaxWidth(Double.MAX_VALUE);
		int currentYear = Year.now().getValue();
		for (int year = currentYear; year >= currentYear - YEAR_LOOKBACK_YEARS; year--) {
			yearField.getItems().add(year);
		}
		questionBookletsBox.setId("exam-assets-question-booklets");
		answerBookletsBox.setId("exam-assets-answer-booklets");
		editExamButton.setId("exam-assets-edit");
		editExamButton.setOnAction(_ -> beginExamDetailsEdit());
		saveButton.setId("exam-assets-save");
		saveButton.setOnAction(_ -> saveExamDetails());
		cancelButton.setId("exam-assets-cancel");

		// Cancel discards only the staged Exam Details edit. Returning to Capture will
		// later be performed by Use Selected Booklet for Capture.
		cancelButton.setOnAction(_ -> cancelExamDetailsEdit());
		refreshMetadataOptions();
		clearSelectedExam();
	}

	private VBox createAnswerFileRow(AnswerFile answerFile) {
		Label heading = new Label(answerFile.getName());
		heading.setStyle(HEADING_STYLE);
		Label source = new Label(sourceFileName(answerFile.getSourceDocument().getRelativePath()));

		// Answer-explanation editing belongs to #72. This first shell deliberately
		// presents only the authoritative Answer asset identity.
		VBox row = new VBox(ROW_SPACING, heading, source);
		row.setId("exam-assets-answer-file-" + answerFile.getId());
		row.setPadding(SECTION_PADDING);
		row.setStyle(BORDER_STYLE);
		return row;
	}

	private VBox createExamDetailsSection() {
		GridPane details = new GridPane();
		details.setHgap(SPACING);
		details.setVgap(ROW_SPACING);
		details.addRow(0, new Label("Provider"), providerField);
		details.addRow(1, new Label("Year"), yearField);
		details.addRow(2, new Label("Assessment"), assessmentField);
		GridPane.setHgrow(providerField, Priority.ALWAYS);
		GridPane.setHgrow(yearField, Priority.ALWAYS);
		GridPane.setHgrow(assessmentField, Priority.ALWAYS);
		Region editSpacer = new Region();
		HBox.setHgrow(editSpacer, Priority.ALWAYS);

		// Edit remains an explicit Exam Details action as specified by the workspace
		// design rather than making every field permanently editable.
		HBox editRow = new HBox(SPACING, editSpacer, editExamButton);
		return createSection("Exam Details", new VBox(ROW_SPACING, details, editRow));
	}

	private VBox createQuestionBookletRow(ExamBooklet booklet) {
		Label heading = new Label(booklet.getName());
		heading.setStyle(HEADING_STYLE);
		Label source = new Label(sourceFileName(booklet.getSourceDocument().getRelativePath()));
		Label planning = new Label("Type: %s    Expected Questions: %s".formatted(
				formatQuestionFormat(booklet.getQuestionFormat()),
				booklet.getExpectedQuestionCount() == null ? "Not recorded" : booklet.getExpectedQuestionCount()));

		// Preserve one row container per persisted booklet so later increments can
		// add View, Name, Type, Expected Questions and Answer controls without
		// changing the overall section structure.
		VBox row = new VBox(ROW_SPACING, heading, source, planning);
		row.setId("exam-assets-question-booklet-" + booklet.getId());
		row.setPadding(SECTION_PADDING);
		row.setStyle(BORDER_STYLE);
		return row;
	}

	private VBox createSection(String headingText, javafx.scene.Node content) {
		Label heading = new Label(headingText);
		heading.setStyle(HEADING_STYLE);
		VBox section = new VBox(ROW_SPACING, heading, content);
		return section;
	}

	private String editedText(ComboBox<String> field) {

		// Editable ComboBoxes keep newly typed values in their editor until committed,
		// so read the editor rather than assuming getValue() has already changed.
		return field.getEditor().getText().strip();
	}

	private String formatExam(Exam exam) {

		// Match the agreed compact selector presentation: year, provider and
		// assessment identify one persisted Exam without repeating Subject.
		return "%d %s %s".formatted(exam.getYear(), exam.getProvider().getName(), exam.getName());
	}

	private String formatQuestionFormat(ExamBookletQuestionFormat format) {
		return switch (format) {
		case MULTIPLE_CHOICE -> "MCQ";
		case WRITTEN_RESPONSE -> "Written Response";
		case MIXED -> "Both";
		case UNSPECIFIED -> "Not recorded";
		};
	}

	private void loadSelectedExam(Exam exam) throws SQLException {
		clearSelectedExam();
		if (exam == null) {
			return;
		}

		stateLabel.setText("State: " + exam.getCaptureState());

		// Editable ComboBoxes maintain both a selected value and editor text. Set both
		// explicitly so persisted metadata remains visible while the controls are
		// disabled and when Edit is subsequently entered.
		String providerName = exam.getProvider().getName();
		providerField.setValue(providerName);
		providerField.getEditor().setText(providerName);

		if (!yearField.getItems().contains(exam.getYear())) {

			// Historical Exams outside the normal suggestion window must still display
			// their exact persisted year.
			yearField.getItems().add(exam.getYear());
		}
		yearField.setValue(exam.getYear());

		String assessmentName = exam.getName();
		assessmentField.setValue(assessmentName);
		assessmentField.getEditor().setText(assessmentName);

		List<ExamBooklet> booklets = examWriter.findExamBooklets(exam);
		if (booklets.isEmpty()) {
			questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		} else {

			// Asset rows always come from authoritative persistence for the selected Exam.
			questionBookletsBox.getChildren().setAll(booklets.stream().map(this::createQuestionBookletRow).toList());
		}

		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(exam);
		if (answerFiles.isEmpty()) {
			answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));
		} else {
			answerBookletsBox.getChildren().setAll(answerFiles.stream().map(this::createAnswerFileRow).toList());
		}

		setExamDetailsEditing(false);
	}

	private void loadSelectedExamSafely(Exam exam) {
		try {
			loadSelectedExam(exam);
		} catch (SQLException exception) {
			clearSelectedExam();

			// A persistence failure must not leave stale Exam or asset information
			// visible as though it were authoritative.
			Alert alert = new Alert(Alert.AlertType.ERROR);
			alert.setTitle("Exam / Assets");
			alert.setHeaderText("Exam assets could not be loaded.");
			alert.setContentText(exception.getMessage());
			alert.showAndWait();
		}
	}

	private void refreshMetadataOptions() {

		// Preferences supply suggestions only. Persisted Exam values remain
		// authoritative even when they are not already present in these lists.
		providerField.getItems().setAll(optionsRepository.getProviders());
		assessmentField.getItems().setAll(optionsRepository.getAssessments());
	}

	private void saveExamDetails() {
		Exam selectedExam = examBox.getValue();
		if (!editingExamDetails || selectedExam == null) {
			return;
		}
		String providerName = editedText(providerField);
		Integer year = yearField.getValue();
		String assessmentName = editedText(assessmentField);
		if (providerName.isBlank() || year == null || assessmentName.isBlank()) {
			showCorrectionError("Complete Provider, Year and Assessment before saving.");
			return;
		}
		try {
			Exam corrected = correctionHandler.correct(selectedExam, providerName, year.intValue(), assessmentName);

			// Retain useful suggestions independently of the Exam identity itself.
			optionsRepository.addProvider(corrected.getProvider().getName());
			optionsRepository.addAssessment(corrected.getName());

			// A fresh repository read proves the workspace is showing authoritative
			// persisted values rather than only the object returned by the correction.
			refresh(corrected.getSubject());
			setExamDetailsEditing(false);
		} catch (SQLException | IOException | IllegalArgumentException | IllegalStateException exception) {
			showCorrectionError(exception.getMessage());
		}
	}

	private void setExamDetailsEditing(boolean editing) {
		editingExamDetails = editing;

		// Metadata remains visible at all times. These controls become mutable only
		// after the user explicitly chooses Edit.
		providerField.setDisable(!editing);
		yearField.setDisable(!editing);
		assessmentField.setDisable(!editing);

		editExamButton.setDisable(editing || examBox.getValue() == null);

		// Cancel and Save apply only to a staged Exam Details edit.
		cancelButton.setDisable(!editing);
		saveButton.setDisable(!editing);

		// Do not allow selection of another Exam while unsaved edits are present.
		examBox.setDisable(editing);
	}

	private void showCorrectionError(String message) {

		// Keep the user in edit mode after failure so the proposed correction can be
		// adjusted without re-entering the workflow.
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam / Assets");
		alert.setHeaderText("Exam metadata could not be saved.");
		alert.setContentText(message);
		alert.showAndWait();
	}

	private String sourceFileName(String relativePath) {
		Path path = Path.of(relativePath);
		Path fileName = path.getFileName();

		// Defensive fallback keeps a malformed-but-readable path visible rather than
		// presenting an empty source label.
		return fileName == null ? relativePath : fileName.toString();
	}

	/**
	 * Performs one authoritative Exam metadata correction.
	 */
	@FunctionalInterface
	public interface ExamCorrectionHandler {

		/**
		 * Corrects one persisted Exam.
		 *
		 * @param exam           Exam being corrected
		 * @param providerName   corrected provider
		 * @param year           corrected year
		 * @param assessmentName corrected assessment name
		 * @return corrected Exam with the same persistent identity
		 * @throws SQLException if persistence fails
		 * @throws IOException  if managed PDF relocation fails
		 */
		Exam correct(Exam exam, String providerName, int year, String assessmentName) throws SQLException, IOException;
	}
}
