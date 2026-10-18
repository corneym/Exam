package au.edu.eq.questionbank.ui.exam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentity;
import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentityProposal;
import au.edu.eq.questionbank.importer.legacy.LegacyExamIdentityResolver;
import au.edu.eq.questionbank.importer.legacy.LegacyExamKey;
import au.edu.eq.questionbank.importer.legacy.LegacyQuestionSheet;
import au.edu.eq.questionbank.model.Exam;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Confirms the authoritative examination identity of each worksheet/year
 * combination in a legacy workbook.
 *
 * <p>
 * Worksheet-name conventions provide initial suggestions. No mapping is
 * authoritative until the user confirms it.
 */
public final class LegacyExamMappingDialog extends Dialog<Map<LegacyExamKey, LegacyExamIdentity>> {

	private static final double SPACING = 8;
	private final List<MappingEditor> editors = new ArrayList<>();
	private final List<ExistingExamChoice> existingExams;

	/**
	 * Opens the confirmation dialog without an existing-Exam snapshot.
	 *
	 * @param owner  owning application window
	 * @param sheets validated workbook worksheets
	 */
	public LegacyExamMappingDialog(Window owner, List<LegacyQuestionSheet> sheets) {
		this(owner, sheets, List.of());
	}

	/**
	 * Opens examination confirmation using the saved Exams in the Working Subject.
	 *
	 * @param owner         owning window
	 * @param sheets        validated workbook worksheets
	 * @param existingExams persisted Exams with their asset-count summaries
	 */
	public LegacyExamMappingDialog(Window owner, List<LegacyQuestionSheet> sheets,
			List<ExistingExamChoice> existingExams) {
		if (sheets == null) {
			throw new NullPointerException("sheets");
		}
		this.existingExams = List.copyOf(Objects.requireNonNull(existingExams, "existingExams"));
		initOwner(owner);
		setTitle("Resolve Legacy Examinations");
		setHeaderText("Confirm the examination associated with each worksheet and year.");
		List<LegacyExamIdentityProposal> proposals = new LegacyExamIdentityResolver().propose(sheets);
		ButtonType confirmType = new ButtonType("Confirm Examinations", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);
		configureContent(proposals);
		configureValidation(confirmType);
		setResultConverter(button -> button == confirmType ? collectMappings() : null);
	}

	private Map<LegacyExamKey, LegacyExamIdentity> collectMappings() {
		Map<LegacyExamKey, LegacyExamIdentity> result = new LinkedHashMap<>();
		for (MappingEditor editor : editors) {
			ExistingExamChoice selected = selectedExistingExam(editor);
			if (selected != null) {

				// The saved spelling is the exact database identity. Do not create a
				// second Exam by returning the worksheet's proposed capitalisation.
				Exam exam = selected.exam();
				result.put(editor.key(), new LegacyExamIdentity(exam.getProvider().getName(), exam.getName()));
			} else {
				result.put(editor.key(), new LegacyExamIdentity(editor.provider().getText().strip(),
						editor.examName().getText().strip()));
			}
		}
		return Map.copyOf(result);
	}

	private void configureContent(List<LegacyExamIdentityProposal> proposals) {
		VBox content = new VBox(SPACING);
		content.setPadding(new Insets(10));
		Label explanation = new Label("Check the proposed Provider and Examination Name. "
				+ "Saved Exams matching without regard to capitalisation must be reused explicitly.");
		explanation.setWrapText(true);
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.add(new Label("Worksheet"), 0, 0);
		grid.add(new Label("Year"), 1, 0);
		grid.add(new Label("Provider"), 2, 0);
		grid.add(new Label("Examination Name"), 3, 0);
		ColumnConstraints worksheetColumn = new ColumnConstraints(140);
		ColumnConstraints yearColumn = new ColumnConstraints(65);
		ColumnConstraints providerColumn = new ColumnConstraints(140);
		ColumnConstraints examColumn = new ColumnConstraints(240);
		examColumn.setHgrow(Priority.ALWAYS);
		grid.getColumnConstraints().addAll(worksheetColumn, yearColumn, providerColumn, examColumn);
		int editorIndex = 1;
		int gridRow = 1;
		for (LegacyExamIdentityProposal proposal : proposals) {
			LegacyExamKey key = new LegacyExamKey(proposal.worksheetName(), proposal.year());
			TextField provider = new TextField(proposal.providerName());
			TextField examName = new TextField(proposal.examName());
			provider.setId("legacy-mapping-provider-" + editorIndex);
			examName.setId("legacy-mapping-exam-" + editorIndex);
			provider.setPromptText("Provider");
			examName.setPromptText("Examination name");
			grid.add(new Label(proposal.worksheetName()), 0, gridRow);
			grid.add(new Label(Integer.toString(proposal.year())), 1, gridRow);
			grid.add(provider, 2, gridRow);
			grid.add(examName, 3, gridRow);
			GridPane.setHgrow(examName, Priority.ALWAYS);
			Label matchStatus = new Label();
			matchStatus.setId("legacy-mapping-status-" + editorIndex);
			matchStatus.setWrapText(true);
			VBox matchPanel = new VBox(4);
			matchPanel.setId("legacy-mapping-matches-" + editorIndex);
			CheckBox acknowledge = new CheckBox("Reuse this saved Exam and preserve its original spelling.");
			acknowledge.setId("legacy-mapping-acknowledge-" + editorIndex);
			acknowledge.setWrapText(true);
			ToggleGroup choiceGroup = new ToggleGroup();
			MappingEditor editor = new MappingEditor(key, provider, examName, matchPanel, matchStatus, acknowledge,
					choiceGroup, new ArrayList<>());
			editors.add(editor);
			grid.add(matchPanel, 0, gridRow + 1, 4, 1);
			refreshMatches(editor);
			editorIndex++;
			gridRow += 2;
		}
		ScrollPane scroll = new ScrollPane(grid);
		scroll.setFitToWidth(true);
		scroll.setPrefViewportHeight(Math.min(520, Math.max(160, gridRow * 60)));
		Label note = new Label("New Exams are created only when no matching saved Exam exists. "
				+ "Existing Question booklets and PDFs are never replaced. "
				+ "Missing legacy Paper links are resolved afterwards in Exam / Assets.");
		note.setWrapText(true);
		content.getChildren().addAll(explanation, scroll, note);
		getDialogPane().setContent(content);
		getDialogPane().setPrefWidth(780);
	}

	private void configureValidation(ButtonType confirmType) {
		Button confirm = (Button) getDialogPane().lookupButton(confirmType);
		confirm.setId("legacy-mapping-confirm");
		Runnable update = () -> confirm
				.setDisable(editors.isEmpty() || editors.stream().anyMatch(editor -> !mappingReady(editor)));
		for (MappingEditor editor : editors) {
			editor.provider().textProperty().addListener((_, _, _) -> {
				refreshMatches(editor);
				update.run();
			});
			editor.examName().textProperty().addListener((_, _, _) -> {
				refreshMatches(editor);
				update.run();
			});
			editor.choices().selectedToggleProperty().addListener((_, _, _) -> update.run());
			editor.acknowledge().selectedProperty().addListener((_, _, _) -> update.run());
		}
		update.run();
	}

	private boolean mappingReady(MappingEditor editor) {
		if (editor.provider().getText().isBlank() || editor.examName().getText().isBlank()) {
			return false;
		}
		if (editor.matches().isEmpty()) {
			return true;
		}
		ExistingExamChoice selected = selectedExistingExam(editor);
		if (selected == null || selected.importBlockReason() != null) {
			return false;
		}
		boolean differentSpelling = !selected.exam().getName().equals(editor.examName().getText().strip())
				|| !selected.exam().getProvider().getName().equals(editor.provider().getText().strip());
		return editor.matches().size() != 1 || !differentSpelling || editor.acknowledge().isSelected();
	}

	private void refreshMatches(MappingEditor editor) {
		ExistingExamChoice previouslySelected = selectedExistingExam(editor);
		Long previousId = previouslySelected == null ? null : previouslySelected.exam().getId();
		String provider = editor.provider().getText().strip();
		String name = editor.examName().getText().strip();
		List<ExistingExamChoice> matches = existingExams.stream()
				.filter(choice -> choice.exam().getYear() == editor.key().year())
				.filter(choice -> choice.exam().getProvider().getName().equalsIgnoreCase(provider))
				.filter(choice -> choice.exam().getName().equalsIgnoreCase(name))
				.sorted(Comparator.comparing((ExistingExamChoice choice) -> choice.exam().getName())
						.thenComparingLong(choice -> choice.exam().getId()))
				.toList();
		editor.matches().clear();
		editor.matches().addAll(matches);
		editor.choices().getToggles().clear();
		editor.matchPanel().getChildren().clear();
		editor.acknowledge().setSelected(false);
		if (matches.isEmpty()) {
			editor.status().setText("No matching saved Exam. A new Exam will be created.");
			editor.matchPanel().getChildren().add(editor.status());
			return;
		}
		boolean allBlocked = matches.stream().allMatch(choice -> choice.importBlockReason() != null);
		if (allBlocked) {
			editor.status().setText("Legacy import unavailable for the saved Exam" + (matches.size() == 1 ? "." : "s.")
					+ " Existing captured or reviewed Questions will not be changed.");
		} else if (matches.size() == 1) {
			editor.status().setText("Matching saved Exam found. Reuse its existing record and PDF assets.");
		} else {
			editor.status().setText("Multiple saved Exams differ only in capitalisation. "
					+ "Select one eligible saved Exam; no automatic merge will occur.");
		}
		editor.matchPanel().getChildren().add(editor.status());
		for (ExistingExamChoice choice : matches) {
			Exam exam = choice.exam();
			RadioButton option = new RadioButton(
					"%s — %s (Exam ID %d) — %d Question, %d Answer booklet(s)".formatted(exam.getName(),
							exam.getCaptureState(), exam.getId(), choice.questionBooklets(), choice.answerBooklets()));
			option.setId("legacy-mapping-existing-" + (editors.indexOf(editor) + 1) + "-" + exam.getId());
			option.setWrapText(true);
			option.setToggleGroup(editor.choices());
			option.setUserData(choice);
			editor.matchPanel().getChildren().add(option);
			if (choice.importBlockReason() != null) {

				// The user can see which saved Exam was found, and why importing
				// into it would be unsafe, without being able to confirm it.
				option.setDisable(true);
				Label reason = new Label(choice.importBlockReason());
				reason.setId("legacy-mapping-blocked-" + (editors.indexOf(editor) + 1) + "-" + exam.getId());
				reason.setWrapText(true);
				editor.matchPanel().getChildren().add(reason);
			} else if (matches.size() == 1 || (previousId != null && previousId == exam.getId())) {
				option.setSelected(true);
			}
		}
		if (matches.size() == 1 && matches.getFirst().importBlockReason() == null) {
			Exam candidate = matches.getFirst().exam();
			boolean differentSpelling = !candidate.getName().equals(name)
					|| !candidate.getProvider().getName().equals(provider);
			if (differentSpelling) {
				editor.status().setText("A saved Exam differs only in capitalisation. "
						+ "Confirm reuse of its original spelling before continuing.");
				editor.matchPanel().getChildren().add(editor.acknowledge());
			}
		}
	}

	private ExistingExamChoice selectedExistingExam(MappingEditor editor) {
		Toggle selected = editor.choices().getSelectedToggle();
		return selected == null ? null : (ExistingExamChoice) selected.getUserData();
	}

	/**
	 * Saved Exam and read-only summary used at legacy identity confirmation.
	 *
	 * @param exam              persisted Exam
	 * @param questionBooklets  saved Question booklet count
	 * @param answerBooklets    saved Answer booklet count
	 * @param importBlockReason rejection reason, or null if eligible
	 */
	public record ExistingExamChoice(Exam exam, int questionBooklets, int answerBooklets, String importBlockReason) {

		/**
		 * Validates the saved Exam and its asset counts.
		 *
		 * @param exam              persisted Exam
		 * @param questionBooklets  number of saved Question booklets
		 * @param answerBooklets    number of saved Answer booklets
		 * @param importBlockReason reason legacy import is prohibited, or null when
		 *                          eligible
		 */
		public ExistingExamChoice {
			Objects.requireNonNull(exam, "exam");
			if (questionBooklets < 0 || answerBooklets < 0) {
				throw new IllegalArgumentException("Booklet counts cannot be negative");
			}
		}

		/**
		 * Creates an existing-Exam choice without an import eligibility restriction.
		 *
		 * <p>
		 * Retains compatibility with callers that only supply the saved Exam and its
		 * asset counts.
		 *
		 * @param exam             persisted Exam
		 * @param questionBooklets number of saved Question booklets
		 * @param answerBooklets   number of saved Answer booklets
		 */
		public ExistingExamChoice(Exam exam, int questionBooklets, int answerBooklets) {
			this(exam, questionBooklets, answerBooklets, null);
		}
	}

	private record MappingEditor(LegacyExamKey key, TextField provider, TextField examName, VBox matchPanel,
			Label status, CheckBox acknowledge, ToggleGroup choices, List<ExistingExamChoice> matches) {
	}
}
