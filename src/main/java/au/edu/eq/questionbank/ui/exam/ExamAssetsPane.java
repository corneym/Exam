package au.edu.eq.questionbank.ui.exam;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
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
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final Runnable cancelHandler;
	private final ComboBox<Exam> examBox = new ComboBox<>();
	private final Label stateLabel = new Label("State: —");
	private final TextField providerField = new TextField();
	private final TextField yearField = new TextField();
	private final TextField assessmentField = new TextField();
	private final VBox questionBookletsBox = new VBox(ROW_SPACING);
	private final VBox answerBookletsBox = new VBox(ROW_SPACING);
	private final Button cancelButton = new Button("Cancel");

	/**
	 * Creates the Exam/Assets workspace.
	 *
	 * @param examWriter    authoritative Exam/booklet persistence
	 * @param answerWriter  authoritative AnswerFile persistence
	 * @param cancelHandler action returning to Question Capture mode
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ExamAssetsPane(SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter, Runnable cancelHandler) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (cancelHandler == null) {
			throw new NullPointerException("cancelHandler");
		}
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
		this.cancelHandler = cancelHandler;

		// Build one persistent workspace whose controls are refreshed from SQLite
		// whenever the application enters Exam/Assets mode.
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

			// Retain the selected Exam across refresh only when that persisted Exam is
			// still available under the authoritative Working Subject.
			selectedExam = exams.stream().filter(exam -> exam.getId() == previousExamId.longValue()).findFirst()
					.orElse(null);
		}
		if (selectedExam == null) {

			// Repository ordering already presents the newest Exam first.
			selectedExam = exams.getFirst();
		}
		examBox.getSelectionModel().select(selectedExam);
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

		// Cancel is the only workflow action in this first read-only increment. Save,
		// Clear and asset-edit actions are introduced only when they have real
		// persistence semantics.
		getChildren().addAll(examHeading, examSelectorGrid, examDetails, questionBooklets, answerBooklets,
				new Separator(), cancelButton);
	}

	private void clearSelectedExam() {
		stateLabel.setText("State: —");
		providerField.clear();
		yearField.clear();
		assessmentField.clear();

		// Empty sections explicitly describe the absence of a selected Exam rather
		// than retaining stale asset rows from the previous selection.
		questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));
	}

	private void configureControls() {
		examBox.setId("exam-assets-exam");
		examBox.setMaxWidth(Double.MAX_VALUE);
		examBox.setConverter(new StringConverter<>() {

			@Override
			public Exam fromString(String text) {

				// Exam selection is persistence-backed only; free-text construction is
				// not part of the existing-Exam workspace.
				return null;
			}

			@Override
			public String toString(Exam exam) {
				return exam == null ? "" : formatExam(exam);
			}
		});
		examBox.valueProperty().addListener((_, _, exam) -> loadSelectedExamSafely(exam));
		stateLabel.setId("exam-assets-state");
		configureReadOnlyField(providerField, "exam-assets-provider");
		configureReadOnlyField(yearField, "exam-assets-year");
		configureReadOnlyField(assessmentField, "exam-assets-assessment");
		questionBookletsBox.setId("exam-assets-question-booklets");
		answerBookletsBox.setId("exam-assets-answer-booklets");
		cancelButton.setId("exam-assets-cancel");
		cancelButton.setOnAction(_ -> cancelHandler.run());
		clearSelectedExam();
	}

	private void configureReadOnlyField(TextField field, String id) {
		field.setId(id);
		field.setEditable(false);
		field.setMaxWidth(Double.MAX_VALUE);

		// These fields deliberately use the same visual form that later editing will
		// use, while remaining read-only until #69 supplies correction behaviour.
		field.setFocusTraversable(false);
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
		return createSection("Exam Details", details);
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
		providerField.setText(exam.getProvider().getName());
		yearField.setText(Integer.toString(exam.getYear()));
		assessmentField.setText(exam.getName());
		List<ExamBooklet> booklets = examWriter.findExamBooklets(exam);
		if (booklets.isEmpty()) {
			questionBookletsBox.getChildren().setAll(new Label("No Question booklets recorded."));
		} else {

			// Rebuild rows directly from authoritative persistence for the selected
			// Exam rather than carrying transient data from the old modal setup UI.
			questionBookletsBox.getChildren().setAll(booklets.stream().map(this::createQuestionBookletRow).toList());
		}
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(exam);
		if (answerFiles.isEmpty()) {
			answerBookletsBox.getChildren().setAll(new Label("No Answer booklets recorded."));
		} else {
			answerBookletsBox.getChildren().setAll(answerFiles.stream().map(this::createAnswerFileRow).toList());
		}
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

	private String sourceFileName(String relativePath) {
		Path path = Path.of(relativePath);
		Path fileName = path.getFileName();

		// Defensive fallback keeps a malformed-but-readable path visible rather than
		// presenting an empty source label.
		return fileName == null ? relativePath : fileName.toString();
	}
}
