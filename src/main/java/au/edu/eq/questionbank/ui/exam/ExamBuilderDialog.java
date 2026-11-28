package au.edu.eq.questionbank.ui.exam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;
import au.edu.eq.questionbank.ui.ScreenAwareWindows;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraft;
import au.edu.eq.questionbank.ui.model.ExamBuilderDraftFile;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Displays and edits the ordered Question selections for a mock examination
 * without modifying Question Bank records.
 */
public final class ExamBuilderDialog extends Dialog<Void> {

	private final Button addButton = new Button("Add Questions...");
	private final ExamBuilderDraft draft;
	private final Button moveDownButton = new Button("Move down");
	private final Button moveUpButton = new Button("Move up");
	private final ListView<PrintableQuestionNumbering.NumberedQuestion> questionList = new ListView<>();
	private final BiFunction<Window, ExamBuilderDraft.Section, Optional<List<Question>>> questionSelector;
	private final Button removeButton = new Button("Remove");
	private final ComboBox<ExamBuilderDraft.Section> sectionBox = new ComboBox<>();
	private final Label summaryLabel = new Label();
	private final Label totalLabel = new Label();
	private final Button transferButton = new Button();
	private final VBox workspace = new VBox(12);
	private final Button generateButton = new Button("Generate Examination...");
	private boolean generating;
	private GenerationAction generationAction;
	private Path managedDataRoot;
	private final TextField paperOnePerusalField = new TextField("5");
	private final TextField paperOneWorkingField = new TextField("90");
	private final TextField paperTwoPerusalField = new TextField("5");
	private final TextField paperTwoWorkingField = new TextField("90");
	private final Label statusLabel = new Label();
	private final TextField yearField = new TextField(Integer.toString(Year.now().getValue()));
	private boolean dirty;
	private ExamBuilderDraftFile draftFileService;
	private final Button openDraftButton = new Button("Open Draft...");
	private final Button saveDraftButton = new Button("Save Draft...");
	private final Supplier<List<Question>> corpusSupplier;

	/**
	 * Creates an examination builder supporting standalone Question selection.
	 *
	 * <p>
	 * Use the constructor accepting a complete corpus supplier when multipart
	 * Question selection is required.
	 *
	 * @param owner            owning application window
	 * @param draft            editable examination selections
	 * @param questionSelector read-only selection operation
	 * @throws NullPointerException if any argument is null
	 */
	public ExamBuilderDialog(Window owner, ExamBuilderDraft draft,
			BiFunction<Window, ExamBuilderDraft.Section, Optional<List<Question>>> questionSelector) {
		this(owner, draft, questionSelector, null);
	}

	/**
	 * Creates an examination builder supporting complete top-level Question
	 * selection.
	 *
	 * @param owner            owning application window
	 * @param draft            editable examination selections
	 * @param questionSelector read-only selection operation
	 * @param corpusSupplier   complete stored Question retrieval, or null to permit
	 *                         standalone selections only
	 * @throws NullPointerException if owner, draft or questionSelector is null
	 */
	public ExamBuilderDialog(Window owner, ExamBuilderDraft draft,
			BiFunction<Window, ExamBuilderDraft.Section, Optional<List<Question>>> questionSelector,
			Supplier<List<Question>> corpusSupplier) {
		this.draft = Objects.requireNonNull(draft, "draft");
		this.questionSelector = Objects.requireNonNull(questionSelector, "questionSelector");
		this.corpusSupplier = corpusSupplier;
		initOwner(Objects.requireNonNull(owner, "owner"));
		configureDialog();
		configureControls();
		configureHandlers();
		configureLayout();
		refresh();
	}

	/**
	 * Enables saving and reopening examination drafts.
	 *
	 * <p>
	 * Call after configuring generation and before displaying the dialog.
	 *
	 * @param service portable examination draft-file service
	 * @throws NullPointerException  if service is null
	 * @throws IllegalStateException if generation has not been configured or
	 *                               draft-file controls already exist
	 */
	public void configureDraftFiles(ExamBuilderDraftFile service) {
		if (generationAction == null) {
			throw new IllegalStateException("Configure generation first.");
		}
		if (draftFileService != null) {
			throw new IllegalStateException("Draft-file controls are already configured.");
		}
		draftFileService = Objects.requireNonNull(service, "service");
		openDraftButton.setId("exam-builder-open-draft");
		saveDraftButton.setId("exam-builder-save-draft");
		openDraftButton.setOnAction(_ -> chooseOpenDraft());
		saveDraftButton.setOnAction(_ -> chooseSaveDraft());
		HBox fileActions = new HBox(8, openDraftButton, saveDraftButton);
		fileActions.setAlignment(Pos.CENTER_LEFT);
		workspace.getChildren().add(0, fileActions);

		// Examination metadata changes are unsaved draft changes,
		// just as adding or reordering a Question is.
		for (TextField field : List.of(yearField, paperOnePerusalField, paperOneWorkingField, paperTwoPerusalField,
				paperTwoWorkingField)) {
			field.textProperty().addListener((_, _, _) -> dirty = true);
		}
		setOnCloseRequest(event -> {
			if (generating || !confirmDiscardChanges()) {
				event.consume();
			}
		});
		dirty = !draft.isEmpty();
	}

	/**
	 * Enables examination metadata entry and PDF generation.
	 *
	 * <p>
	 * Call once before displaying the dialog. Generated files must be placed
	 * outside the application's managed data root.
	 *
	 * @param action          examination generation operation
	 * @param managedDataRoot authoritative managed data directory
	 * @throws NullPointerException  if either argument is null
	 * @throws IllegalStateException if generation is already configured
	 */
	public void configureGeneration(GenerationAction action, Path managedDataRoot) {
		if (generationAction != null) {
			throw new IllegalStateException("Generation is already configured.");
		}
		this.generationAction = Objects.requireNonNull(action, "action");
		this.managedDataRoot = Objects.requireNonNull(managedDataRoot, "managedDataRoot");
		yearField.setId("exam-builder-year");
		paperOnePerusalField.setId("exam-builder-paper-one-perusal");
		paperOneWorkingField.setId("exam-builder-paper-one-working");
		paperTwoPerusalField.setId("exam-builder-paper-two-perusal");
		paperTwoWorkingField.setId("exam-builder-paper-two-working");
		generateButton.setId("exam-builder-generate");
		statusLabel.setId("exam-builder-status");
		statusLabel.setWrapText(true);
		GridPane metadata = new GridPane();
		metadata.setHgap(12);
		metadata.setVgap(8);
		for (TextField field : List.of(yearField, paperOnePerusalField, paperOneWorkingField, paperTwoPerusalField,
				paperTwoWorkingField)) {
			field.setPrefColumnCount(6);
			field.setMaxWidth(90);
		}
		metadata.add(new Label("Assessment year"), 0, 0);
		metadata.add(yearField, 1, 0);
		metadata.add(new Label("Perusal (minutes)"), 1, 1);
		metadata.add(new Label("Working (minutes)"), 2, 1);
		metadata.add(new Label("Paper 1"), 0, 2);
		metadata.add(paperOnePerusalField, 1, 2);
		metadata.add(paperOneWorkingField, 2, 2);
		metadata.add(new Label("Paper 2"), 0, 3);
		metadata.add(paperTwoPerusalField, 1, 3);
		metadata.add(paperTwoWorkingField, 2, 3);
		workspace.getChildren().addAll(0, List.of(metadata, new Separator()));
		workspace.getChildren().addAll(new Separator(), generateButton, statusLabel);
		generateButton.setOnAction(_ -> chooseGenerationDirectory());
		setOnCloseRequest(event -> {
			if (generating) {
				event.consume();
			}
		});
		updateActionState();
	}

	void startGeneration(Path destination) {
		if (generating || generationAction == null || draft.isEmpty()) {
			return;
		}
		PrintableExamSettings settings;
		PrintableAssessmentPlan plan;
		Path outputDirectory;
		try {
			settings = readSettings();
			plan = draft.buildPlan();
			outputDirectory = destination.toRealPath();
			requireOutsideManagedData(outputDirectory);
			if (!Files.isDirectory(outputDirectory)) {
				throw new IOException("Select an existing output directory.");
			}
		} catch (IOException | IllegalArgumentException | IllegalStateException failure) {
			statusLabel.setText("Cannot generate: " + failure.getMessage());
			return;
		}
		GenerationAction action = generationAction;
		generating = true;
		workspace.setDisable(true);
		getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(true);
		statusLabel.setText("Generating examination PDFs...");

		// Capture the validated settings and ordered plan before
		// starting background work. Never read JavaFX controls
		// from the generation thread.
		Task<Void> task = new Task<>() {

			@Override
			protected Void call() throws IOException {
				action.generate(settings, plan, outputDirectory);
				return null;
			}
		};
		task.setOnSucceeded(_ -> finishGeneration("Examination generated: " + outputDirectory));
		task.setOnFailed(_ -> {
			Throwable failure = task.getException();
			finishGeneration("Generation failed: " + (failure == null ? "Unknown error"
					: Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())));
		});
		task.setOnCancelled(_ -> finishGeneration("Generation cancelled."));
		Thread worker = new Thread(task, "mock-examination-generation");

		// Do not abandon a PDF write solely because the JavaFX
		// application thread is terminating.
		worker.setDaemon(false);
		worker.start();
	}

	private void addQuestion() {
		ExamBuilderDraft.Section section = sectionBox.getValue();
		List<Question> selected = questionSelector.apply(getDialogPane().getScene().getWindow(), section)
				.orElse(List.of());
		if (selected.isEmpty()) {
			return;
		}
		try {

			// Revalidate against the complete authoritative corpus,
			// not merely the Questions returned by the filtered chooser.
			List<Question> corpus = corpusSupplier == null
					? selected.stream().filter(question -> question.getSourceQuestion() == null).toList()
					: List.copyOf(corpusSupplier.get());
			if (!draft.addSelections(section, selected, corpus)) {
				statusLabel.setText("The selection contains an unavailable, " + "incomplete or duplicate Question.");
				return;
			}
		} catch (RuntimeException failure) {
			statusLabel.setText("Cannot add selected Questions: " + failure.getMessage());
			return;
		}
		changed();
		selectQuestion(selected.getLast().getId());
	}

	private void changed() {
		dirty = true;
		refresh();
	}

	private void chooseGenerationDirectory() {
		try {

			// Validate the metadata before asking the user
			// to select a destination.
			readSettings();
		} catch (IllegalArgumentException failure) {
			statusLabel.setText(failure.getMessage());
			return;
		}
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle("Select Examination Output Directory");
		File destination = chooser.showDialog(getDialogPane().getScene().getWindow());
		if (destination != null) {
			startGeneration(destination.toPath());
		}
	}

	private void chooseOpenDraft() {
		FileChooser chooser = createDraftChooser();
		chooser.setTitle("Open Examination Draft");
		File selected = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
		if (selected == null) {
			return;
		}
		if (!confirmDiscardChanges()) {
			return;
		}
		try {
			openDraft(selected.toPath());
		} catch (IOException | IllegalArgumentException failure) {
			statusLabel.setText("Cannot open draft: " + failure.getMessage());
		}
	}

	private void chooseSaveDraft() {
		try {
			readSettings();
		} catch (IllegalArgumentException failure) {
			statusLabel.setText(failure.getMessage());
			return;
		}
		FileChooser chooser = createDraftChooser();
		chooser.setTitle("Save Examination Draft");
		chooser.setInitialFileName(draft.getSubject().getName() + "-mock-exam.exam.json");
		File selected = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
		if (selected == null) {
			return;
		}
		Path destination = selected.toPath();
		if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exam.json")) {
			destination = destination.resolveSibling(destination.getFileName() + ".exam.json");
		}
		if (Files.exists(destination)) {
			Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
			confirm.initOwner(getDialogPane().getScene().getWindow());
			confirm.setTitle("Replace Examination Draft");
			confirm.setHeaderText("Replace the existing draft file?");
			confirm.setContentText(destination.toString());
			if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
				return;
			}
		}
		try {
			saveDraft(destination);
		} catch (IOException | IllegalArgumentException failure) {
			statusLabel.setText("Cannot save draft: " + failure.getMessage());
		}
	}

	private void configureControls() {
		addButton.setId("exam-builder-add");
		moveDownButton.setId("exam-builder-down");
		moveUpButton.setId("exam-builder-up");
		questionList.setId("exam-builder-questions");
		removeButton.setId("exam-builder-remove");
		sectionBox.setId("exam-builder-section");
		summaryLabel.setId("exam-builder-summary");
		totalLabel.setId("exam-builder-total");
		transferButton.setId("exam-builder-transfer");
		sectionBox.getItems().setAll(ExamBuilderDraft.Section.values());
		sectionBox.getSelectionModel().selectFirst();
		sectionBox.setMaxWidth(Double.MAX_VALUE);
		summaryLabel.setWrapText(true);
		questionList.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(PrintableQuestionNumbering.NumberedQuestion item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setGraphic(null);
					return;
				}
				Question question = item.question();
				Exam exam = question.getExam();
				String generatedLabel = item.label().replace("(", "");
				setText("%s    %s %d — %s — %s — Q%s    [%d %s]".formatted(generatedLabel, exam.getProvider().getName(),
						exam.getYear(), exam.getName(), question.getBooklet().getName(), question.getQuestionCode(),
						question.getMarks(), question.getMarks() == 1 ? "mark" : "marks"));
			}
		});
	}

	private void configureDialog() {
		setTitle("Exam Builder");
		setHeaderText("Mock examination — " + draft.getSubject().getName());
		setResizable(true);
		getDialogPane().setId("exam-builder-dialog");
		getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
		Rectangle2D bounds = ScreenAwareWindows.visualBounds(getOwner());
		getDialogPane().setPrefWidth(Math.min(1100, bounds.getWidth() - 32));
		getDialogPane().setPrefHeight(Math.min(740, bounds.getHeight() - 48));
	}

	private void configureHandlers() {
		addButton.setOnAction(_ -> addQuestion());
		removeButton.setOnAction(_ -> removeQuestion());
		moveUpButton.setOnAction(_ -> moveQuestion(-1));
		moveDownButton.setOnAction(_ -> moveQuestion(1));
		transferButton.setOnAction(_ -> transferQuestion());
		sectionBox.valueProperty().addListener((_, _, _) -> refresh());
		questionList.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> updateActionState());
	}

	private void configureLayout() {
		HBox sectionRow = new HBox(8, sectionBox, addButton);
		sectionRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(sectionBox, Priority.ALWAYS);
		FlowPane questionActions = new FlowPane(8, 8, moveUpButton, moveDownButton, removeButton, transferButton);
		workspace.setPadding(new Insets(12));
		workspace.getChildren().setAll(summaryLabel, new Separator(), sectionRow, questionList, questionActions,
				new Separator(), totalLabel);
		VBox.setVgrow(questionList, Priority.ALWAYS);
		getDialogPane().setContent(workspace);
	}

	private boolean confirmDiscardChanges() {
		if (!dirty) {
			return true;
		}
		Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
		confirm.initOwner(getDialogPane().getScene().getWindow());
		confirm.setTitle("Unsaved Examination Draft");
		confirm.setHeaderText("Discard unsaved examination changes?");
		confirm.setContentText("Save the current draft first if you " + "want to retain these selections.");
		ButtonType discard = new ButtonType("Discard Changes", ButtonBar.ButtonData.OK_DONE);
		confirm.getButtonTypes().setAll(discard, ButtonType.CANCEL);
		return confirm.showAndWait().orElse(ButtonType.CANCEL) == discard;
	}

	private FileChooser createDraftChooser() {
		FileChooser chooser = new FileChooser();
		chooser.getExtensionFilters()
				.setAll(new FileChooser.ExtensionFilter("Examination drafts (*.exam.json)", "*.exam.json"));
		return chooser;
	}

	private void finishGeneration(String message) {
		generating = false;
		workspace.setDisable(false);
		getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(false);
		statusLabel.setText(message);
		updateActionState();
	}

	private void moveQuestion(int offset) {
		long questionId = selectedQuestionId();
		if (questionId <= 0) {
			return;
		}
		ExamBuilderDraft.Section section = sectionBox.getValue();
		int destination = draft.adjacentWholeQuestionIndex(section, questionId, offset);
		if (destination >= 0 && draft.move(section, questionId, destination)) {
			changed();
			selectQuestion(questionId);
		}
	}

	private void openDraft(Path file) throws IOException {
		ExamBuilderDraftFile.LoadedDraft loaded = draftFileService.load(file, draft.getSubject());

		// Preserve the draft object referenced by the chooser,
		// while adopting the loaded examination's persistent identity.
		draft.replaceWith(loaded.draft());
		PrintableExamSettings settings = loaded.settings();
		yearField.setText(Integer.toString(settings.year()));
		paperOnePerusalField.setText(Integer.toString(settings.paperOnePerusalMinutes()));
		paperOneWorkingField.setText(Integer.toString(settings.paperOneWorkingMinutes()));
		paperTwoPerusalField.setText(Integer.toString(settings.paperTwoPerusalMinutes()));
		paperTwoWorkingField.setText(Integer.toString(settings.paperTwoWorkingMinutes()));
		sectionBox.getSelectionModel().selectFirst();
		refresh();
		dirty = false;
		statusLabel.setText(draft.isFinalised() ? "Finalised examination opened (read-only): " + file
				: "Examination draft opened: " + file);
	}

	private int readInteger(TextField field, String description) {
		try {
			return Integer.parseInt(field.getText().trim());
		} catch (NumberFormatException failure) {
			throw new IllegalArgumentException(description + " must be a whole number.", failure);
		}
	}

	private PrintableExamSettings readSettings() {
		return new PrintableExamSettings(draft.getSubject().getName(), readInteger(yearField, "Assessment year"),
				readInteger(paperOnePerusalField, "Paper 1 perusal time"),
				readInteger(paperOneWorkingField, "Paper 1 working time"),
				readInteger(paperTwoPerusalField, "Paper 2 perusal time"),
				readInteger(paperTwoWorkingField, "Paper 2 working time"));
	}

	private void refresh() {
		long previousId = selectedQuestionId();
		ExamBuilderDraft.Section section = sectionBox.getValue();
		if (draft.isEmpty() || section == null) {
			questionList.getItems().clear();
		} else {
			PrintableAssessmentPlan plan = draft.buildPlan();

			// Use the same numbering plan that will subsequently
			// be consumed by the PDF generation service.
			var numbered = switch (section) {
			case MULTIPLE_CHOICE -> plan.getMultipleChoice();
			case PAPER_ONE_WRITTEN -> plan.getPaperOne();
			case PAPER_TWO_WRITTEN -> plan.getPaperTwo();
			};
			questionList.getItems().setAll(numbered);
		}
		int mcqMarks = draft.getTotalMarks(ExamBuilderDraft.Section.MULTIPLE_CHOICE);
		int paperOneMarks = draft.getTotalMarks(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN);
		int paperTwoMarks = draft.getTotalMarks(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN);
		summaryLabel.setText("Paper 1 MCQ: %d marks    |    ".formatted(mcqMarks)
				+ "Paper 1 written: %d marks    |    ".formatted(paperOneMarks)
				+ "Paper 2 written: %d marks".formatted(paperTwoMarks));
		totalLabel.setText("%d selected Questions — Total: %d marks".formatted(draft.getTopLevelSelectedCount(),
				mcqMarks + paperOneMarks + paperTwoMarks));
		selectQuestion(previousId);
		updateActionState();
	}

	private void removeQuestion() {
		long questionId = selectedQuestionId();
		if (questionId > 0 && draft.remove(sectionBox.getValue(), questionId)) {
			changed();
		}
	}

	private void requireOutsideManagedData(Path existingDirectory) throws IOException {
		Path destination = existingDirectory.toRealPath();
		Path managedRoot = managedDataRoot.toRealPath();

		// Resolve real paths so a directory alias or symbolic
		// link cannot redirect output into managed source assets.
		if (destination.startsWith(managedRoot)) {
			throw new IOException("Select a destination outside the " + "managed Question Bank data directory.");
		}
	}

	private void saveDraft(Path file) throws IOException {
		Path destination = file.toAbsolutePath().normalize();
		requireOutsideManagedData(destination.getParent());
		draftFileService.save(destination, draft, readSettings());
		dirty = false;
		statusLabel.setText("Examination draft saved: " + destination);
	}

	private long selectedQuestionId() {
		var selected = questionList.getSelectionModel().getSelectedItem();
		return selected == null ? -1 : selected.question().getId();
	}

	private void selectQuestion(long questionId) {
		questionList.getSelectionModel().clearSelection();
		for (var item : questionList.getItems()) {
			if (item.question().getId() == questionId) {
				questionList.getSelectionModel().select(item);
				questionList.scrollTo(item);
				return;
			}
		}
	}

	private void transferQuestion() {
		ExamBuilderDraft.Section source = sectionBox.getValue();
		ExamBuilderDraft.Section destination = transferTarget();
		long questionId = selectedQuestionId();
		if (destination != null && questionId > 0 && draft.transfer(source, destination, questionId)) {
			changed();
			sectionBox.setValue(destination);
			selectQuestion(questionId);
		}
	}

	private ExamBuilderDraft.Section transferTarget() {
		ExamBuilderDraft.Section section = sectionBox.getValue();
		if (section == null) {
			return null;
		}
		return switch (section) {
		case MULTIPLE_CHOICE -> null;
		case PAPER_ONE_WRITTEN -> ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		case PAPER_TWO_WRITTEN -> ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		};
	}

	private void updateActionState() {
		boolean readOnly = draft.isFinalised();
		long questionId = selectedQuestionId();
		boolean selected = questionId > 0;
		ExamBuilderDraft.Section section = sectionBox.getValue();
		setHeaderText("Mock examination — " + draft.getSubject().getName() + (readOnly ? " — FINALISED" : " — DRAFT"));
		addButton.setDisable(readOnly);
		moveUpButton.setDisable(readOnly || !selected || draft.adjacentWholeQuestionIndex(section, questionId, -1) < 0);
		moveDownButton
				.setDisable(readOnly || !selected || draft.adjacentWholeQuestionIndex(section, questionId, 1) < 0);
		removeButton.setDisable(readOnly || !selected);
		ExamBuilderDraft.Section target = transferTarget();
		transferButton.setVisible(target != null);
		transferButton.setManaged(target != null);
		transferButton.setDisable(readOnly || !selected || target == null);
		transferButton.setText(
				target == ExamBuilderDraft.Section.PAPER_TWO_WRITTEN ? "Transfer to Paper 2" : "Transfer to Paper 1");

		// Committed examination timing and year metadata are read-only.
		for (TextField field : List.of(yearField, paperOnePerusalField, paperOneWorkingField, paperTwoPerusalField,
				paperTwoWorkingField)) {
			field.setEditable(!readOnly);
		}
		generateButton.setDisable(generationAction == null || draft.isEmpty() || generating);
	}

	/**
	 * Performs PDF generation using an immutable numbered assessment plan and
	 * validated examination metadata.
	 */
	@FunctionalInterface
	public interface GenerationAction {

		/**
		 * Generates the examination documents.
		 *
		 * @param settings    examination metadata
		 * @param plan        validated and numbered examination selections
		 * @param destination output directory
		 * @throws IOException if document generation fails
		 */
		void generate(PrintableExamSettings settings, PrintableAssessmentPlan plan, Path destination)
				throws IOException;
	}
}
