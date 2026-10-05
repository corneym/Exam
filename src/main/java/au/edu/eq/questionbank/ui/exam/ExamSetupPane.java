package au.edu.eq.questionbank.ui.exam;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Presents persisted Exam structure and source assets for one application
 * Subject.
 * <p>
 * The pane reads the same authoritative Exam, ExamBooklet and AnswerFile models
 * used by capture and legacy import. Selecting a booklet emits that persisted
 * booklet as the entry point into Question capture.
 */
public final class ExamSetupPane extends VBox {

	private static final double SPACING = 10.0;
	private static final double EXAM_FIELD_WIDTH = 420.0;
	private static final double ASSET_LIST_HEIGHT = 120.0;
	private static final Insets PADDING = new Insets(12);
	private final SqliteExamWriter examWriter;
	private final SqliteAnswerWriter answerWriter;
	private final Consumer<ExamBooklet> openBookletHandler;
	private final Label subjectLabel = new Label("No Subject selected");
	private final ComboBox<Exam> examField = new ComboBox<>();
	private final Label lifecycleLabel = new Label("State: —");
	private final Label assetSummaryLabel = new Label("No Exam selected");
	private final ListView<ExamBooklet> bookletList = new ListView<>();
	private final ListView<String> answerFileList = new ListView<>();
	private final Button openBookletButton = new Button("Open Selected Booklet for Capture");
	private final Consumer<ExamBooklet> inspectBookletHandler;
	private final Button inspectBookletButton = new Button("Inspect / Set Expected Questions...");

	/**
	 * Creates an Exam setup pane backed by the authoritative assessment
	 * repositories.
	 *
	 * @param examWriter            Exam and booklet repository
	 * @param answerWriter          Answer-file repository
	 * @param inspectBookletHandler action performed when a persisted booklet is
	 *                              opened for inspection
	 * @param openBookletHandler    action performed when a persisted booklet is
	 *                              selected for capture
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public ExamSetupPane(SqliteExamWriter examWriter, SqliteAnswerWriter answerWriter,
			Consumer<ExamBooklet> inspectBookletHandler, Consumer<ExamBooklet> openBookletHandler) {
		if (examWriter == null) {
			throw new NullPointerException("examWriter");
		}
		if (answerWriter == null) {
			throw new NullPointerException("answerWriter");
		}
		if (inspectBookletHandler == null) {
			throw new NullPointerException("inspectBookletHandler");
		}
		if (openBookletHandler == null) {
			throw new NullPointerException("openBookletHandler");
		}
		this.examWriter = examWriter;
		this.answerWriter = answerWriter;
		this.inspectBookletHandler = inspectBookletHandler;
		this.openBookletHandler = openBookletHandler;

		// The setup surface is a browser over persisted Exam structure rather than
		// another editable copy of Exam metadata.
		configureControls();
		getChildren().addAll(subjectLabel, new Label("Exam:"), examField, lifecycleLabel, assetSummaryLabel,
				new Separator(), new Label("Question booklets:"), bookletList, inspectBookletButton, openBookletButton,
				new Separator(), new Label("Answer / marking assets:"), answerFileList);
		setSpacing(SPACING);
		setPadding(PADDING);
		VBox.setVgrow(bookletList, Priority.ALWAYS);
		VBox.setVgrow(answerFileList, Priority.ALWAYS);
	}

	/**
	 * Reloads the Exams and assets available for one Subject.
	 * <p>
	 * A {@code null} Subject clears the setup surface because no Exam may be
	 * selected without application-level Subject context.
	 *
	 * @param subject current application Subject, or {@code null}
	 * @throws SQLException if Exam persistence cannot be read
	 */
	public void refresh(Subject subject) throws SQLException {
		Long previousExamId = examField.getValue() == null ? null : Long.valueOf(examField.getValue().getId());
		examField.getSelectionModel().clearSelection();
		examField.getItems().clear();
		clearExamAssets();
		if (subject == null) {
			subjectLabel.setText("No Subject selected");
			return;
		}
		subjectLabel.setText("Subject: " + subject.getName());
		List<Exam> exams = examWriter.findExamsForSubject(subject);
		examField.getItems().setAll(exams);
		if (exams.isEmpty()) {
			assetSummaryLabel.setText("No Exams are recorded for this Subject.");
			return;
		}
		Exam selectedExam = null;

		// Preserve the user's Exam selection across refresh when that Exam still
		// belongs to the current Subject.
		if (previousExamId != null) {
			selectedExam = exams.stream().filter(exam -> exam.getId() == previousExamId.longValue()).findFirst()
					.orElse(null);
		}
		if (selectedExam == null) {

			// Repository ordering presents the most recent Exam first, giving setup a
			// useful initial selection without activating capture automatically.
			selectedExam = exams.getFirst();
		}
		examField.getSelectionModel().select(selectedExam);
	}

	private void clearExamAssets() {
		bookletList.getItems().clear();
		answerFileList.getItems().clear();
		bookletList.getSelectionModel().clearSelection();
		lifecycleLabel.setText("State: —");
		assetSummaryLabel.setText("No Exam selected");
		openBookletButton.setDisable(true);
	}

	private void configureControls() {
		examField.setId("exam-setup-exam");
		examField.setPrefWidth(EXAM_FIELD_WIDTH);
		examField.setConverter(new StringConverter<>() {

			@Override
			public Exam fromString(String text) {

				// Exam Setup is selection-only; free-text Exam construction does not
				// belong in the ComboBox converter.
				return null;
			}

			@Override
			public String toString(Exam exam) {
				return exam == null ? "" : formatExam(exam);
			}
		});
		examField.valueProperty().addListener((_, _, selectedExam) -> {
			try {

				// Asset lists always follow the selected persisted Exam.
				loadExamAssets(selectedExam);
			} catch (SQLException exception) {
				clearExamAssets();
				showLoadError(exception);
			}
		});
		bookletList.setId("exam-setup-booklets");
		bookletList.setPrefHeight(ASSET_LIST_HEIGHT);
		bookletList.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(ExamBooklet booklet, boolean empty) {
				super.updateItem(booklet, empty);

				// Show structural planning metadata alongside each real source asset.
				setText(empty || booklet == null ? null : formatBooklet(booklet));
			}
		});
		answerFileList.setId("exam-setup-answer-files");
		answerFileList.setPrefHeight(ASSET_LIST_HEIGHT);
		lifecycleLabel.setId("exam-setup-state");
		assetSummaryLabel.setId("exam-setup-summary");
		inspectBookletButton.setId("exam-setup-inspect-booklet");
		inspectBookletButton.setDisable(true);
		inspectBookletButton.setOnAction(_ -> {
			ExamBooklet selectedBooklet = bookletList.getSelectionModel().getSelectedItem();
			if (selectedBooklet != null) {

				// Inspection does not activate Question capture. The application decides
				// how the persisted source document is presented read-only.
				inspectBookletHandler.accept(selectedBooklet);
			}
		});
		openBookletButton.setId("exam-setup-open-booklet");
		inspectBookletButton.setDisable(true);
		openBookletButton.setDisable(true);
		bookletList.getSelectionModel().selectedItemProperty().addListener((_, _, selectedBooklet) -> {

			// Both inspection and capture operate only on a real persisted source
			// booklet. Neither action invents a placeholder asset.
			boolean noBookletSelected = selectedBooklet == null;
			inspectBookletButton.setDisable(noBookletSelected);
			openBookletButton.setDisable(noBookletSelected);
		});
		openBookletButton.setOnAction(_ -> {
			ExamBooklet selectedBooklet = bookletList.getSelectionModel().getSelectedItem();
			if (selectedBooklet != null) {

				// The application owns actual PDF activation. This pane emits only the
				// authoritative persisted booklet chosen by the user.
				openBookletHandler.accept(selectedBooklet);
			}
		});
	}

	private List<String> describeAnswerFiles(List<ExamBooklet> booklets, List<AnswerFile> answerFiles)
			throws SQLException {
		Map<Long, List<String>> bookletNamesByAnswerFile = new HashMap<>();
		for (ExamBooklet booklet : booklets) {
			AnswerFile assigned = answerWriter.findAnswerFile(booklet);
			if (assigned == null) {
				continue;
			}

			// Build the assignment description from persisted booklet mappings so a
			// shared marking guide is displayed once with every booklet that uses it.
			bookletNamesByAnswerFile.computeIfAbsent(Long.valueOf(assigned.getId()), _ -> new ArrayList<>())
					.add(booklet.getName());
		}
		List<String> descriptions = new ArrayList<>();
		for (AnswerFile answerFile : answerFiles) {
			List<String> assignedBooklets = bookletNamesByAnswerFile.getOrDefault(Long.valueOf(answerFile.getId()),
					List.of());
			String assignmentText = assignedBooklets.isEmpty() ? "unassigned" : String.join(", ", assignedBooklets);
			descriptions.add(answerFile.getName() + " — " + assignmentText);
		}
		return List.copyOf(descriptions);
	}

	private String formatBooklet(ExamBooklet booklet) {
		String expectedQuestions = booklet.getExpectedQuestionCount() == null ? "expected Questions not set"
				: booklet.getExpectedQuestionCount() + " expected Questions";
		return "%s — %s — %s".formatted(booklet.getName(), booklet.getQuestionFormat(), expectedQuestions);
	}

	private String formatExam(Exam exam) {
		return "%d %s — %s".formatted(exam.getYear(), exam.getProvider().getName(), exam.getName());
	}

	private String formatExpectedCount(Integer expectedCount) {
		return expectedCount == null ? "not set" : expectedCount.toString();
	}

	private void loadExamAssets(Exam exam) throws SQLException {
		clearExamAssets();
		if (exam == null) {
			return;
		}
		List<ExamBooklet> booklets = examWriter.findExamBooklets(exam);
		List<AnswerFile> answerFiles = answerWriter.findAnswerFiles(exam);
		ExamAssetExpectations expectations = examWriter.findExamAssetExpectations(exam);
		bookletList.getItems().setAll(booklets);
		answerFileList.getItems().setAll(describeAnswerFiles(booklets, answerFiles));
		lifecycleLabel.setText("State: " + exam.getCaptureState());

		// Expected counts remain distinct from the number of assets currently
		// available; an unknown expectation is never inferred from current rows.
		// Expected counts remain distinct from the number of assets currently
		// available; an unknown expectation is never inferred from current rows.
		assetSummaryLabel.setText(("""
				Question booklets: %d available / %s expected    \
				Answer files: %d available / %s expected
				""").formatted(expectations.availableQuestionBookletCount(),
				formatExpectedCount(expectations.expectedQuestionBookletCount()),
				expectations.availableAnswerFileCount(), formatExpectedCount(expectations.expectedAnswerFileCount()))
				.strip());
	}

	private void showLoadError(SQLException exception) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setTitle("Exam Setup");
		alert.setHeaderText("Exam assets could not be loaded.");
		alert.setContentText(exception.getMessage());
		alert.showAndWait();
	}
}
