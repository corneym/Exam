package au.edu.eq.questionbank.ui.correction;

import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Collects the explicit metadata decisions required before a legacy Question is
 * split into separately captured multipart Questions.
 */
public final class LegacyQuestionSplitDialog extends javafx.scene.control.Dialog<LegacyQuestionSplitDialog.Result> {

	private static final int DIALOG_WIDTH = 850;
	private static final int FORM_SPACING = 8;
	private static final Insets FORM_PADDING = new Insets(10);
	private final Question originalQuestion;
	private final CurriculumRepository curriculumRepository;
	private final SharedQuestionContext existingSharedContext;
	private final TextField sourceQuestionCodeField = new TextField();
	private final ComboBox<PreambleChoice> preambleChoiceBox = new ComboBox<>();
	private final ComboBox<String> retainedAnswerPartBox = new ComboBox<>();
	private final VBox partsBox = new VBox(FORM_SPACING);
	private final Button addPartButton = new Button("Add Part");
	private final Button removePartButton = new Button("Remove Last Part");
	private final ButtonType continueButtonType = new ButtonType("Continue to Capture", ButtonBar.ButtonData.OK_DONE);
	private final List<PartEditor> partEditors = new ArrayList<>();

	/**
	 * Creates the split-definition dialog.
	 *
	 * @param owner                 owner window
	 * @param originalQuestion      legacy Question to split
	 * @param curriculumRepository  curriculum hierarchy lookup
	 * @param existingSharedContext established destination context that may be
	 *                              reused, or {@code null}
	 */
	public LegacyQuestionSplitDialog(Window owner, Question originalQuestion, CurriculumRepository curriculumRepository,
			SharedQuestionContext existingSharedContext) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		if (originalQuestion == null) {
			throw new NullPointerException("originalQuestion");
		}
		if (curriculumRepository == null) {
			throw new NullPointerException("curriculumRepository");
		}
		this.originalQuestion = originalQuestion;
		this.curriculumRepository = curriculumRepository;
		this.existingSharedContext = existingSharedContext;
		initOwner(owner);
		setTitle("Split Question");
		setHeaderText(createHeaderText());
		setResizable(true);
		configureControls();
		createInitialParts();
		getDialogPane().setContent(createContent());
		getDialogPane().getButtonTypes().addAll(continueButtonType, ButtonType.CANCEL);
		configureContinueButton();
		configureResultConverter();
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
	}

	private void addPart() {

		// Every resulting legacy split part is a written-response Question.
		PartEditor editor = createPartEditor("", "", originalQuestion.getClassification());
		partEditors.add(editor);
		refreshParts();
	}

	private void collectClassifications(CurriculumNode node, List<CurriculumNode> classifications) {
		if (node.getLevel() == CurriculumLevel.SUBTOPIC || node.getLevel() == CurriculumLevel.DESCRIPTOR) {
			classifications.add(node);
		}
		for (CurriculumNode child : curriculumRepository.findChildren(node)) {
			collectClassifications(child, classifications);
		}
	}

	private void configureContinueButton() {
		Button continueButton = (Button) getDialogPane().lookupButton(continueButtonType);
		continueButton.setId("legacy-split-continue");
		refreshContinueState();
	}

	private void configureControls() {
		sourceQuestionCodeField.setId("legacy-split-source-code");
		sourceQuestionCodeField.setText(originalQuestion.getQuestionCode());

		// The source identity is confirmed here rather than derived from entered
		// part codes. Correct an incorrect legacy Question code before splitting.
		sourceQuestionCodeField.setEditable(false);
		preambleChoiceBox.setId("legacy-split-preamble-choice");
		preambleChoiceBox.getItems().add(PreambleChoice.NO_SHARED_PREAMBLE);
		preambleChoiceBox.getItems().add(PreambleChoice.CAPTURE_NEW_SHARED_PREAMBLE);
		if (existingSharedContext != null) {
			preambleChoiceBox.getItems().add(PreambleChoice.REUSE_EXISTING_SHARED_PREAMBLE);
		}
		preambleChoiceBox.setCellFactory(_ -> createPreambleChoiceCell());
		preambleChoiceBox.setButtonCell(createPreambleChoiceCell());
		preambleChoiceBox.setMaxWidth(Double.MAX_VALUE);
		preambleChoiceBox.valueProperty().addListener((_, _, _) -> refreshContinueState());
		retainedAnswerPartBox.setId("legacy-split-answer-part");
		retainedAnswerPartBox.setMaxWidth(Double.MAX_VALUE);
		retainedAnswerPartBox.valueProperty().addListener((_, _, _) -> refreshContinueState());
		addPartButton.setId("legacy-split-add-part");
		removePartButton.setId("legacy-split-remove-part");
		addPartButton.setOnAction(_ -> addPart());
		removePartButton.setOnAction(_ -> removeLastPart());
	}

	private void configureResultConverter() {
		setResultConverter(buttonType -> {
			if (buttonType != continueButtonType) {
				return null;
			}
			List<PartDefinition> parts = partEditors.stream().map(PartEditor::toDefinition).toList();
			int retainedPartIndex = retainedPartIndex(parts);
			SharedQuestionContext reusedContext = preambleChoiceBox
					.getValue() == PreambleChoice.REUSE_EXISTING_SHARED_PREAMBLE ? existingSharedContext : null;
			return new Result(sourceQuestionCodeField.getText().trim(), parts, retainedPartIndex,
					preambleChoiceBox.getValue(), reusedContext);
		});
	}

	private HBox createAnswerOwnershipControls() {
		Label label = createFieldLabel("Existing Answer belongs to", "legacy-split-answer-part-label");
		Label explanation = new Label(
				"The selected part will retain the original Question row and its existing Answer.");
		explanation.setWrapText(true);
		VBox explanationBox = new VBox(4, retainedAnswerPartBox, explanation);
		HBox.setHgrow(explanationBox, Priority.ALWAYS);
		return new HBox(FORM_SPACING, label, explanationBox);
	}

	private VBox createContent() {
		VBox content = new VBox(FORM_SPACING);
		content.setPadding(FORM_PADDING);
		Label explanation = new Label("""
				Define the resulting Question parts before capturing their replacement source regions.

				No Question data is changed until every part has been captured and the complete split is saved.
				""");
		explanation.setWrapText(true);
		GridPane summary = new GridPane();
		summary.setHgap(FORM_SPACING);
		summary.setVgap(FORM_SPACING);
		Label sourceLabel = createFieldLabel("Source Question", "legacy-split-source-code-label");
		Label preambleLabel = createFieldLabel("Shared preamble", "legacy-split-preamble-label");
		summary.add(sourceLabel, 0, 0);
		summary.add(sourceQuestionCodeField, 1, 0);
		summary.add(preambleLabel, 0, 1);
		summary.add(preambleChoiceBox, 1, 1);
		GridPane.setHgrow(sourceQuestionCodeField, Priority.ALWAYS);
		GridPane.setHgrow(preambleChoiceBox, Priority.ALWAYS);
		content.getChildren().addAll(explanation, summary, createPartsHeading(), partsBox, createPartButtons());
		if (originalQuestion.hasAnswer()) {
			content.getChildren().add(createAnswerOwnershipControls());
		}
		return content;
	}

	private Label createFieldLabel(String text, String id) {
		Label label = new Label(text);
		label.setId(id);
		label.setMinWidth(Region.USE_PREF_SIZE);
		return label;
	}

	private String createHeaderText() {

		// Identify the exact persisted Question being corrected without implying any
		// destination part structure before the user confirms it.
		return originalQuestion.getExam().getProvider().getName() + " " + originalQuestion.getExam().getYear() + " — "
				+ originalQuestion.getBooklet().getName() + " — Question " + originalQuestion.getQuestionCode();
	}

	private void createInitialParts() {
		String sourceCode = originalQuestion.getQuestionCode();

		// Legacy multipart correction creates written-response parts only.
		partEditors.add(createPartEditor(sourceCode + "a", "", originalQuestion.getClassification()));
		partEditors.add(createPartEditor(sourceCode + "b", "", originalQuestion.getClassification()));
		refreshParts();
	}

	private HBox createPartButtons() {
		HBox buttons = new HBox(FORM_SPACING, addPartButton, removePartButton);
		buttons.setAlignment(Pos.CENTER_LEFT);
		return buttons;
	}

	private PartEditor createPartEditor(String questionCode, String marks, CurriculumNode classification) {
		PartEditor editor = new PartEditor(questionCode, marks, classification);
		editor.questionCodeField().textProperty().addListener((_, _, _) -> {
			refreshAnswerPartChoices();
			refreshContinueState();
		});
		editor.marksField().textProperty().addListener((_, _, _) -> refreshContinueState());
		editor.classificationBox().valueProperty().addListener((_, _, _) -> refreshContinueState());
		return editor;
	}

	private Label createPartsHeading() {
		Label label = new Label("Resulting Questions");
		label.setStyle("-fx-font-weight: bold;");
		return label;
	}

	private ListCell<PreambleChoice> createPreambleChoiceCell() {
		return new ListCell<>() {

			@Override
			protected void updateItem(PreambleChoice choice, boolean empty) {
				super.updateItem(choice, empty);
				if (empty || choice == null) {
					setText(null);
					return;
				}
				setText(choice.displayText());
			}
		};
	}

	private List<CurriculumNode> findClassificationChoices(SyllabusVersion syllabusVersion) {
		List<CurriculumNode> classifications = new ArrayList<>();
		for (CurriculumNode root : curriculumRepository.findRootNodes(syllabusVersion)) {
			collectClassifications(root, classifications);
		}
		return List.copyOf(classifications);
	}

	private boolean formIsValid() {
		if (partEditors.size() < 2) {
			return false;
		}
		if (preambleChoiceBox.getValue() == null) {
			return false;
		}
		List<String> codes = new ArrayList<>();
		for (PartEditor editor : partEditors) {
			if (!editor.isValid()) {
				return false;
			}
			String code = editor.questionCodeField().getText().trim();
			if (codes.contains(code)) {
				return false;
			}
			codes.add(code);
		}
		if (originalQuestion.hasAnswer() && retainedAnswerPartBox.getValue() == null) {
			return false;
		}
		return true;
	}

	private void refreshAnswerPartChoices() {
		if (!originalQuestion.hasAnswer()) {
			return;
		}
		String previous = retainedAnswerPartBox.getValue();
		List<String> partCodes = partEditors.stream().map(editor -> editor.questionCodeField().getText().trim())
				.filter(code -> !code.isBlank()).toList();
		retainedAnswerPartBox.getItems().setAll(partCodes);
		if (previous != null && partCodes.contains(previous)) {
			retainedAnswerPartBox.setValue(previous);
		} else {
			retainedAnswerPartBox.getSelectionModel().clearSelection();
		}
	}

	private void refreshContinueState() {
		Button continueButton = (Button) getDialogPane().lookupButton(continueButtonType);
		if (continueButton != null) {
			continueButton.setDisable(!formIsValid());
		}
	}

	private void refreshPartEditorIds() {
		for (int index = 0; index < partEditors.size(); index++) {
			partEditors.get(index).setControlIds(index);
		}
	}

	private void refreshParts() {
		partsBox.getChildren().setAll(partEditors.stream().map(PartEditor::node).toList());
		refreshPartEditorIds();
		refreshAnswerPartChoices();
		removePartButton.setDisable(partEditors.size() <= 2);
		refreshContinueState();
	}

	private void removeLastPart() {
		if (partEditors.size() <= 2) {
			return;
		}
		partEditors.removeLast();
		refreshParts();
	}

	private int retainedPartIndex(List<PartDefinition> parts) {
		if (!originalQuestion.hasAnswer()) {

			// Without an Answer, retaining the first part is only a persistence
			// identity choice and has no user-visible semantic consequence.
			return 0;
		}
		String selectedCode = retainedAnswerPartBox.getValue();
		for (int index = 0; index < parts.size(); index++) {
			if (parts.get(index).questionCode().equals(selectedCode)) {
				return index;
			}
		}
		throw new IllegalStateException("Selected Answer part is no longer present");
	}

	public enum PreambleChoice {

		NO_SHARED_PREAMBLE("No shared preamble"), CAPTURE_NEW_SHARED_PREAMBLE("Capture new shared preamble"),
		REUSE_EXISTING_SHARED_PREAMBLE("Reuse existing shared preamble");

		private final String displayText;

		PreambleChoice(String displayText) {
			this.displayText = displayText;
		}

		String displayText() {
			return displayText;
		}
	}

	public record PartDefinition(String questionCode, int marks, CurriculumNode classification,
			QuestionResponseType responseType) {

		public PartDefinition {
			if (questionCode == null || questionCode.isBlank()) {
				throw new IllegalArgumentException("questionCode must not be blank");
			}
			if (marks < 1) {
				throw new IllegalArgumentException("marks must be positive");
			}
			if (classification == null) {
				throw new NullPointerException("classification");
			}
			if (responseType != QuestionResponseType.WRITTEN_RESPONSE) {

				// The dialog definition mirrors the legacy split domain rule rather than
				// exposing response types that cannot occur in this workflow.
				throw new IllegalArgumentException("Legacy split parts must be written response");
			}
			questionCode = questionCode.trim();
		}
	}

	public record Result(String sourceQuestionCode, List<PartDefinition> parts, int retainedPartIndex,
			PreambleChoice preambleChoice, SharedQuestionContext existingSharedContext) {

		public Result {
			if (sourceQuestionCode == null || sourceQuestionCode.isBlank()) {
				throw new IllegalArgumentException("sourceQuestionCode must not be blank");
			}
			if (parts == null || parts.size() < 2) {
				throw new IllegalArgumentException("At least two parts are required");
			}
			if (retainedPartIndex < 0 || retainedPartIndex >= parts.size()) {
				throw new IllegalArgumentException("retainedPartIndex is outside the part list");
			}
			if (preambleChoice == null) {
				throw new NullPointerException("preambleChoice");
			}
			if (preambleChoice == PreambleChoice.REUSE_EXISTING_SHARED_PREAMBLE && existingSharedContext == null) {
				throw new IllegalArgumentException("Existing shared context is required for reuse");
			}
			sourceQuestionCode = sourceQuestionCode.trim();
			parts = List.copyOf(parts);
		}
	}

	private final class PartEditor {

		private final TextField questionCodeField = new TextField();
		private final TextField marksField = new TextField();
		private final ComboBox<CurriculumNode> classificationBox = new ComboBox<>();
		private final GridPane node = new GridPane();

		private PartEditor(String questionCode, String marks, CurriculumNode classification) {
			questionCodeField.setText(questionCode);
			marksField.setText(marks);
			classificationBox.getItems()
					.setAll(findClassificationChoices(originalQuestion.getClassification().getSyllabusVersion()));
			classificationBox.setValue(classification);
			classificationBox.setMaxWidth(Double.MAX_VALUE);

			// Response type is intentionally absent: this workflow can only create
			// written-response parts.
			node.setHgap(FORM_SPACING);
			node.setVgap(4);
			node.addRow(0, new Label("Question"), questionCodeField, new Label("Marks"), marksField);
			node.addRow(1, new Label("Classification"), classificationBox);
			GridPane.setColumnSpan(classificationBox, 3);
			GridPane.setHgrow(questionCodeField, Priority.ALWAYS);
			GridPane.setHgrow(classificationBox, Priority.ALWAYS);
			node.setPadding(new Insets(8));
			node.setStyle("-fx-border-color: #b0b0b0;" + "-fx-border-width: 1;" + "-fx-border-radius: 3;");
		}

		private ComboBox<CurriculumNode> classificationBox() {
			return classificationBox;
		}

		private boolean isValid() {
			if (questionCodeField.getText() == null || questionCodeField.getText().isBlank()) {
				return false;
			}
			if (classificationBox.getValue() == null) {
				return false;
			}
			try {

				// Split marks remain explicit positive written-response marks.
				return Integer.parseInt(marksField.getText().trim()) > 0;
			} catch (NumberFormatException exception) {
				return false;
			}
		}

		private TextField marksField() {
			return marksField;
		}

		private GridPane node() {
			return node;
		}

		private TextField questionCodeField() {
			return questionCodeField;
		}

		private void setControlIds(int index) {
			questionCodeField.setId("legacy-split-part-" + index + "-code");
			marksField.setId("legacy-split-part-" + index + "-marks");
			classificationBox.setId("legacy-split-part-" + index + "-classification");
		}

		private PartDefinition toDefinition() {

			// The response type is fixed by the split workflow rather than entered by
			// the user.
			return new PartDefinition(questionCodeField.getText().trim(), Integer.parseInt(marksField.getText().trim()),
					classificationBox.getValue(), QuestionResponseType.WRITTEN_RESPONSE);
		}
	}
}
