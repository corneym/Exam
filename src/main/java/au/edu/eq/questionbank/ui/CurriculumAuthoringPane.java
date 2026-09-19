package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.repository.curriculum.CurriculumAuthoringWriter;
import au.edu.eq.questionbank.service.curriculum.CurriculumAuthoringSession;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNumberingService;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftTextExporter;
import au.edu.eq.questionbank.service.curriculum.CurriculumLifecycleService;
import au.edu.eq.questionbank.service.curriculum.CurriculumSourcePdfService;
import au.edu.eq.questionbank.ui.pdf.PdfFilePicker;
import au.edu.eq.questionbank.ui.pdf.PdfWorkspacePane;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * PDF-assisted workspace for explicitly authoring a curriculum hierarchy.
 * <p>
 * The user selects syllabus text, explicitly chooses the node type, and
 * explicitly establishes parent context by selecting a node in the draft tree.
 * Numeric codes are derived from that authored hierarchy.
 */
final class CurriculumAuthoringPane extends BorderPane implements AutoCloseable {

	private static final int EDITOR_ROWS = 4;
	private static final int VALIDATION_ROWS = 3;
	private static final int SECTION_SPACING = 8;
	private static final int SOURCE_PADDING = 8;
	private static final int CONTROL_SPACING = 6;
	private static final int PDF_MIN_HEIGHT = 180;
	private static final int TEXT_CAPTURE_MIN_HEIGHT = 220;
	private static final double SOURCE_DIVIDER_POSITION = 0.40;
	private static final int TREE_PADDING = 10;
	private static final double WORKSPACE_DIVIDER_POSITION = 0.50;
	private static final int PAGE_TEXT_ROWS = 18;
	private static final int PAGE_TEXT_MIN_HEIGHT = 300;
	private final Stage ownerStage;
	private final PdfFilePicker pdfFilePicker;
	private final CurriculumDraft draft;
	private final CurriculumDraftNumberingService numbering = new CurriculumDraftNumberingService();
	private final CurriculumDraftTextExporter textExporter = new CurriculumDraftTextExporter();
	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();
	private final Label pdfPathLabel = new Label("No syllabus PDF selected");
	private final TextArea pageTextArea = new TextArea();
	private final TreeItem<CurriculumDraftNode> treeRoot = new TreeItem<>();
	private final TreeView<CurriculumDraftNode> draftTree = new TreeView<>(treeRoot);
	private final Button addUnitButton = new Button("Add _Unit");
	private final Button addTopicButton = new Button("Add _Topic");
	private final Button addSubtopicButton = new Button("Add _Subtopic");
	private final Button addDescriptorButton = new Button("Add _Descriptor");
	private final Label contextLabel = new Label("No parent selected.");
	private final Label selectedLevelValue = new Label("-");
	private final Label selectedCodeValue = new Label("-");
	private final Label selectedPageValue = new Label("-");
	private final TextArea editTextArea = new TextArea();
	private final Button updateTextButton = new Button("Update text");
	private final Button deleteButton = new Button("Delete subtree");
	private final Button moveUpButton = new Button("Move up");
	private final Button moveDownButton = new Button("Move down");
	private final TextArea validationArea = new TextArea();
	private final Label statusLabel = new Label();
	private Path syllabusPdfPath;
	private final CurriculumAuthoringSession authoringSession;
	private final CurriculumAuthoringWriter authoringWriter;
	private final CurriculumSourcePdfService sourcePdfService;
	private final CurriculumLifecycleService lifecycleService;
	private final Button browseButton = new Button("Browse...");
	private final Button saveButton = new Button("Save");
	private final Button finaliseButton = new Button("Mark Final");
	private final Button reopenButton = new Button("Reopen for editing");
	private boolean dirty;

	// The editor remains bound to this node while tree selection is changing.
	private Long editorDraftId;
	private boolean changingTree;
	private final Label lifecycleLabel = new Label();

	CurriculumAuthoringPane(Stage ownerStage, Path curriculumDataRoot, CurriculumAuthoringSession authoringSession,
			CurriculumAuthoringWriter authoringWriter, CurriculumSourcePdfService sourcePdfService,
			CurriculumLifecycleService lifecycleService) {
		this(ownerStage, curriculumDataRoot, requireDraft(authoringSession), authoringSession, authoringWriter,
				sourcePdfService, lifecycleService);
	}

	CurriculumAuthoringPane(Stage ownerStage, Path curriculumDataRoot, CurriculumDraft draft) {
		this(ownerStage, curriculumDataRoot, draft, null, null, null, null);
	}

	private CurriculumAuthoringPane(Stage ownerStage, Path curriculumDataRoot, CurriculumDraft draft,
			CurriculumAuthoringSession authoringSession, CurriculumAuthoringWriter authoringWriter,
			CurriculumSourcePdfService sourcePdfService, CurriculumLifecycleService lifecycleService) {
		if (ownerStage == null) {
			throw new NullPointerException("ownerStage");
		}
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		if (draft == null) {
			throw new NullPointerException("draft");
		}
		if (authoringSession != null) {
			if (authoringWriter == null) {
				throw new NullPointerException("authoringWriter");
			}
			if (sourcePdfService == null) {
				throw new NullPointerException("sourcePdfService");
			}
			if (lifecycleService == null) {
				throw new NullPointerException("lifecycleService");
			}
		}
		this.ownerStage = ownerStage;
		this.pdfFilePicker = new PdfFilePicker(curriculumDataRoot);
		this.draft = draft;
		this.authoringSession = authoringSession;
		this.authoringWriter = authoringWriter;
		this.sourcePdfService = sourcePdfService;
		this.lifecycleService = lifecycleService;
		configurePdfArea();
		configureTree();
		configureCaptureButtons();
		configureCorrectionControls();
		configurePersistenceControls();
		configureLayout();
		rebuildTree(null);
		refreshValidation();
		refreshLifecycleState();
		openManagedPdfIfAvailable();
	}

	private static CurriculumDraft requireDraft(CurriculumAuthoringSession session) {
		if (session == null) {
			throw new NullPointerException("authoringSession");
		}
		return session.draft();
	}

	@Override
	public void close() throws Exception {
		pdfWorkspace.close();
	}

	Path attachSyllabusPdf(Path sourcePdf) throws IOException {
		requirePersistentMode();
		if (!isEditable()) {
			throw new IllegalStateException("Final curriculum must be reopened before changing its source PDF");
		}
		Path managedPdf = sourcePdfService.attachPdf(authoringSession, sourcePdf);
		openSyllabusPdf(managedPdf);
		refreshLifecycleState();
		statusLabel.setText("Attached managed syllabus PDF.");
		return managedPdf;
	}

	/**
	 * Exports the current transient draft for inspection.
	 *
	 * @param target output text file
	 * @throws IOException if the preview cannot be written
	 */
	void exportDraft(Path target) throws IOException {
		textExporter.write(draft, syllabusPdfPath, target);
	}

	void finaliseCurriculum() {
		requirePersistentMode();
		applyPendingEditorText();
		lifecycleService.finalise(authoringSession);
		dirty = false;
		refreshLifecycleState();
		statusLabel.setText("Curriculum marked Final.");
	}

	boolean hasUnsavedChanges() {
		return isPersistentMode() && (dirty || hasPendingEditorText());
	}

	/** Includes FINAL views, because they can be reopened for editing in place. */
	boolean isForSyllabus(long syllabusVersionId) {
		return isPersistentMode() && authoringSession.syllabusVersion().getId() == syllabusVersionId;
	}

	/**
	 * Opens an authoritative syllabus PDF.
	 *
	 * @param path syllabus PDF
	 */
	void openSyllabusPdf(Path path) {
		if (path == null) {
			throw new NullPointerException("path");
		}
		syllabusPdfPath = path.toAbsolutePath().normalize();
		pdfWorkspace.openViewerPdf(syllabusPdfPath);
		pdfPathLabel.setText(syllabusPdfPath.toString());
		refreshPageText();
	}

	void reopenCurriculum() {
		requirePersistentMode();
		lifecycleService.reopen(authoringSession);
		dirty = false;
		refreshLifecycleState();
		statusLabel.setText("Curriculum reopened for editing.");
	}

	void saveCurriculum() {
		requirePersistentMode();
		if (!isEditable()) {
			throw new IllegalStateException("Final curriculum must be reopened before saving");
		}
		applyPendingEditorText();
		authoringWriter.save(authoringSession);
		dirty = false;
		refreshLifecycleState();
		statusLabel.setText("Curriculum saved.");
	}

	/**
	 * Applies editor wording in memory only; validation failure leaves it intact.
	 */
	private void applyPendingEditorText() {
		if (!isEditable() || !hasPendingEditorText()) {
			return;
		}
		CurriculumDraftNode updated = numbering.updateText(draft, editorDraftId, editTextArea.getText());
		TreeItem<CurriculumDraftNode> item = findTreeItem(treeRoot, editorDraftId);
		if (item != null) {
			item.setValue(updated);
		}
		markDirty();
		refreshValidation();
	}

	private void browseForPdf() {
		if (!isEditable()) {
			statusLabel.setText("Reopen the curriculum before changing its source PDF.");
			return;
		}
		Path selected = pdfFilePicker.chooseAnyPdf(ownerStage, "Select Curriculum Syllabus PDF");
		if (selected == null) {
			return;
		}
		if (!isPersistentMode()) {
			openSyllabusPdf(selected);
			return;
		}
		try {
			attachSyllabusPdf(selected);
		} catch (IOException | RuntimeException e) {
			statusLabel.setText("Unable to attach syllabus PDF: " + e.getMessage());
		}
	}

	private TreeItem<CurriculumDraftNode> buildTreeItem(CurriculumDraftNode node, Set<Long> visited,
			Set<Long> collapsedDraftIds) {
		TreeItem<CurriculumDraftNode> item = new TreeItem<>(node);
		item.setExpanded(!collapsedDraftIds.contains(node.draftId()));
		if (!visited.add(node.draftId())) {
			return item;
		}
		for (CurriculumDraftNode child : draft.childrenOf(node.draftId())) {
			if (!visited.contains(child.draftId())) {
				item.getChildren().add(buildTreeItem(child, visited, collapsedDraftIds));
			}
		}
		return item;
	}

	private void captureSelectedText(CurriculumLevel level) {
		if (!isEditable()) {
			statusLabel.setText("Reopen the curriculum before editing.");
			return;
		}
		if (syllabusPdfPath == null) {
			statusLabel.setText("Select a syllabus PDF first.");
			return;
		}
		String selectedText = pageTextArea.getSelectedText();
		if (selectedText == null || selectedText.isBlank()) {
			statusLabel.setText("Highlight the syllabus wording to capture first.");
			return;
		}
		CurriculumDraftNode selectedParent = selectedNode();
		if (!isPermittedParent(selectedParent, level)) {
			statusLabel.setText(parentInstruction(level));
			return;
		}

		// Units are always root nodes. Tree selection provides parent context only for
		// Topic, Subtopic and Descriptor capture.
		Long parentDraftId = level == CurriculumLevel.UNIT ? null : selectedParent.draftId();
		try {
			applyPendingEditorText();
			CurriculumDraftNode added = numbering.addNode(draft, level, selectedText.strip(), parentDraftId,
					pdfWorkspace.getCurrentPageNumber());
			markDirty();
			Long selectionAfterCapture;
			if (level == CurriculumLevel.UNIT) {

				// A new Unit becomes the current context so topics can be captured immediately.
				selectionAfterCapture = added.draftId();
			} else {

				// Keep the existing parent selected. This is important for capturing several
				// siblings in succession.
				selectionAfterCapture = parentDraftId;
			}
			rebuildTree(selectionAfterCapture);
			refreshValidation();
			statusLabel.setText("Added " + added.level() + " " + currentNode(added.draftId()).code() + " from page "
					+ added.sourcePageNumber() + ".");
		} catch (IllegalArgumentException | NullPointerException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private Set<Long> collapsedDraftIds() {
		Set<Long> collapsedDraftIds = new HashSet<>();
		for (TreeItem<CurriculumDraftNode> child : treeRoot.getChildren()) {
			collectCollapsedDraftIds(child, collapsedDraftIds);
		}
		return collapsedDraftIds;
	}

	private void collectCollapsedDraftIds(TreeItem<CurriculumDraftNode> item, Set<Long> collapsedDraftIds) {
		CurriculumDraftNode node = item.getValue();
		if (node != null && !item.getChildren().isEmpty() && !item.isExpanded()) {
			collapsedDraftIds.add(node.draftId());
		}
		for (TreeItem<CurriculumDraftNode> child : item.getChildren()) {
			collectCollapsedDraftIds(child, collapsedDraftIds);
		}
	}

	private void configureCaptureButton(Button button, String id, CurriculumLevel level) {
		button.setId(id);
		button.setMnemonicParsing(true);
		button.setOnAction(_ -> captureSelectedText(level));
	}

	private void configureCaptureButtons() {
		configureCaptureButton(addUnitButton, "add-curriculum-unit", CurriculumLevel.UNIT);
		configureCaptureButton(addTopicButton, "add-curriculum-topic", CurriculumLevel.TOPIC);
		configureCaptureButton(addSubtopicButton, "add-curriculum-subtopic", CurriculumLevel.SUBTOPIC);
		configureCaptureButton(addDescriptorButton, "add-curriculum-descriptor", CurriculumLevel.DESCRIPTOR);
	}

	private void configureCorrectionControls() {
		editTextArea.setId("curriculum-edit-text");
		editTextArea.setWrapText(true);
		editTextArea.setStyle("-fx-font-size: 16px;");
		editTextArea.setPrefRowCount(EDITOR_ROWS);
		editTextArea.textProperty().addListener((_, _, _) -> refreshDirtyLabel());
		updateTextButton.setId("update-curriculum-text");
		updateTextButton.setOnAction(_ -> updateSelectedText());
		deleteButton.setId("delete-curriculum-node");
		deleteButton.setOnAction(_ -> confirmAndDeleteSelectedNode());
		moveUpButton.setId("move-curriculum-node-up");
		moveUpButton.setOnAction(_ -> moveSelectedNode(true));
		moveDownButton.setId("move-curriculum-node-down");
		moveDownButton.setOnAction(_ -> moveSelectedNode(false));
		validationArea.setId("curriculum-validation");
		validationArea.setEditable(false);
		validationArea.setWrapText(true);
		validationArea.setPrefRowCount(VALIDATION_ROWS);
		statusLabel.setId("curriculum-authoring-status");
		statusLabel.setWrapText(true);
		contextLabel.setWrapText(true);
	}

	private void configureLayout() {
		browseButton.setId("browse-syllabus-pdf");
		browseButton.setOnAction(_ -> browseForPdf());
		HBox pdfHeader = new HBox(SECTION_SPACING, new Label("Syllabus PDF:"), pdfPathLabel, browseButton);
		pdfHeader.setPadding(new Insets(SOURCE_PADDING));
		VBox textCaptureBox = createTextCaptureBox();

		// The PDF is reference material. Give roughly half of the left-hand workspace
		// to the PDF and half to extracted/selectable text.
		pdfWorkspace.setMinHeight(PDF_MIN_HEIGHT);
		textCaptureBox.setMinHeight(TEXT_CAPTURE_MIN_HEIGHT);
		SplitPane sourceSplit = new SplitPane(pdfWorkspace, textCaptureBox);
		sourceSplit.setId("curriculum-source-split");
		sourceSplit.setOrientation(Orientation.VERTICAL);

		// Start with less space for the PDF and more for the selectable extracted text.
		// The divider remains draggable by the user.
		sourceSplit.setDividerPositions(SOURCE_DIVIDER_POSITION);
		VBox treeBox = createDraftTreeBox();

		// The curriculum tree is now the complete right-hand working pane.
		SplitPane workspaceSplit = new SplitPane(sourceSplit, treeBox);
		workspaceSplit.setDividerPositions(WORKSPACE_DIVIDER_POSITION);
		setTop(pdfHeader);
		setCenter(workspaceSplit);
	}

	private VBox createTextCaptureBox() {
		Label captureInstruction = new Label(
				"Highlight wording below, select its parent in the curriculum tree, " + "then choose the node type.");
		captureInstruction.setWrapText(true);
		HBox captureButtons = new HBox(CONTROL_SPACING, addUnitButton, addTopicButton, addSubtopicButton,
				addDescriptorButton);
		VBox textCaptureBox = new VBox(CONTROL_SPACING, new Label("Current page text"), captureInstruction,
				pageTextArea, captureButtons, contextLabel);
		textCaptureBox.setPadding(new Insets(SOURCE_PADDING));
		VBox.setVgrow(pageTextArea, Priority.ALWAYS);
		return textCaptureBox;
	}

	private VBox createDraftTreeBox() {
		GridPane selectedDetails = new GridPane();
		selectedDetails.setHgap(SECTION_SPACING);
		selectedDetails.setVgap(CONTROL_SPACING);
		selectedDetails.add(new Label("Level:"), 0, 0);
		selectedDetails.add(selectedLevelValue, 1, 0);
		selectedDetails.add(new Label("Code:"), 0, 1);
		selectedDetails.add(selectedCodeValue, 1, 1);
		selectedDetails.add(new Label("Source page:"), 0, 2);
		selectedDetails.add(selectedPageValue, 1, 2);
		HBox correctionButtons = new HBox(CONTROL_SPACING, updateTextButton, moveUpButton, moveDownButton,
				deleteButton);
		HBox persistenceButtons = new HBox(CONTROL_SPACING, saveButton, finaliseButton, reopenButton);
		VBox treeBox = new VBox(SECTION_SPACING, lifecycleLabel, persistenceButtons, new Label("Draft curriculum"),
				draftTree, new Label("Selected node"), selectedDetails, editTextArea, correctionButtons,
				new Label("Validation"), validationArea, statusLabel);
		treeBox.setPadding(new Insets(TREE_PADDING));
		VBox.setVgrow(draftTree, Priority.ALWAYS);
		return treeBox;
	}

	private void configurePdfArea() {
		pageTextArea.setId("syllabus-page-text");
		pageTextArea.setEditable(false);
		pageTextArea.setWrapText(true);
		pageTextArea.setPrefRowCount(PAGE_TEXT_ROWS);
		pageTextArea.setMinHeight(PAGE_TEXT_MIN_HEIGHT);
		pageTextArea.setStyle("-fx-font-size: 16px;");
		pdfWorkspace.setSelectionAvailable(_ -> false);
		pdfWorkspace.setPageChangedHandler(_ -> refreshPageText());
	}

	private void configurePersistenceControls() {
		saveButton.setId("save-curriculum");
		saveButton.setOnAction(_ -> saveFromUi());
		finaliseButton.setId("finalise-curriculum");
		finaliseButton.setOnAction(_ -> finaliseFromUi());
		reopenButton.setId("reopen-curriculum");
		reopenButton.setOnAction(_ -> reopenFromUi());
		lifecycleLabel.setId("curriculum-lifecycle");
		lifecycleLabel.setWrapText(true);
	}

	private void configureTree() {
		draftTree.setId("curriculum-draft-tree");
		draftTree.setShowRoot(false);
		draftTree.setCellFactory(_ -> new TreeCell<>() {

			@Override
			protected void updateItem(CurriculumDraftNode node, boolean empty) {
				super.updateItem(node, empty);
				if (empty || node == null) {
					setText(null);
					return;
				}
				setText(node.code() + "  " + node.level() + " — " + node.name());
			}
		});
		draftTree.getSelectionModel().selectedItemProperty().addListener((_, previous, _) -> {
			if (changingTree) {
				return;
			}
			try {
				applyPendingEditorText();
				refreshSelectedNodeDetails();
			} catch (IllegalArgumentException e) {

				// Keep invalid wording visible and bound to its original node.
				changingTree = true;
				try {
					draftTree.getSelectionModel().select(previous);
				} finally {
					changingTree = false;
				}
				statusLabel.setText(e.getMessage());
			}
		});
	}

	private void confirmAndDeleteSelectedNode() {
		if (!isEditable()) {
			statusLabel.setText("Reopen the curriculum before editing.");
			return;
		}
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			return;
		}
		ButtonType deleteSubtreeButton = new ButtonType("Delete subtree", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
		alert.initOwner(ownerStage);
		alert.setTitle("Delete curriculum node");
		alert.setHeaderText("Delete " + selected.code() + " and all of its children?");
		alert.setContentText("This removes the selected subtree " + "from the current in-memory draft.");
		alert.getButtonTypes().setAll(deleteSubtreeButton, cancelButton);
		ButtonType result = alert.showAndWait().orElse(cancelButton);
		if (result != deleteSubtreeButton) {
			return;
		}
		List<CurriculumDraftNode> removed = numbering.removeSubtree(draft, selected.draftId());
		markDirty();
		rebuildTree(null);
		refreshValidation();
		statusLabel.setText("Deleted " + removed.size() + (removed.size() == 1 ? " node." : " nodes."));
	}

	private CurriculumDraftNode currentNode(long draftId) {
		return draft.findNode(draftId).orElseThrow();
	}

	private void finaliseFromUi() {
		try {
			finaliseCurriculum();
		} catch (IllegalArgumentException | IllegalStateException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private TreeItem<CurriculumDraftNode> findTreeItem(TreeItem<CurriculumDraftNode> item, long draftId) {
		CurriculumDraftNode value = item.getValue();
		if (value != null && value.draftId() == draftId) {
			return item;
		}
		for (TreeItem<CurriculumDraftNode> child : item.getChildren()) {
			TreeItem<CurriculumDraftNode> match = findTreeItem(child, draftId);
			if (match != null) {
				return match;
			}
		}
		return null;
	}

	private boolean hasPendingEditorText() {
		return editorDraftId != null
				&& draft.findNode(editorDraftId).map(node -> !node.name().equals(editTextArea.getText())).orElse(false);
	}

	private boolean isEditable() {
		return !isPersistentMode() || !authoringSession.syllabusVersion().isCurriculumFinal();
	}

	private boolean isPermittedParent(CurriculumDraftNode parent, CurriculumLevel childLevel) {
		return switch (childLevel) {
		case UNIT -> true;
		case TOPIC -> parent != null && parent.level() == CurriculumLevel.UNIT;
		case SUBTOPIC -> parent != null && parent.level() == CurriculumLevel.TOPIC && !usesDirectTopicDescriptors();
		case DESCRIPTOR -> {
			if (parent == null) {
				yield false;
			}
			if (parent.level() == CurriculumLevel.SUBTOPIC) {
				yield true;
			}
			yield parent.level() == CurriculumLevel.TOPIC && !usesSubtopics();
		}
		};
	}

	private boolean isPersistentMode() {
		return authoringSession != null;
	}

	private void markDirty() {
		if (!isPersistentMode()) {
			return;
		}
		dirty = true;
		refreshDirtyLabel();
	}

	private void moveSelectedNode(boolean upward) {
		if (!isEditable()) {
			statusLabel.setText("Reopen the curriculum before editing.");
			return;
		}
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			return;
		}
		try {
			applyPendingEditorText();
		} catch (IllegalArgumentException e) {
			statusLabel.setText(e.getMessage());
			return;
		}
		long draftId = selected.draftId();
		boolean moved = upward ? numbering.moveUp(draft, draftId) : numbering.moveDown(draft, draftId);
		if (!moved) {
			return;
		}
		markDirty();
		rebuildTree(draftId);
		refreshValidation();
		statusLabel.setText("Moved " + currentNode(draftId).code() + ".");
	}

	private void openManagedPdfIfAvailable() {
		if (!isPersistentMode()) {
			return;
		}
		Path managedPdf = sourcePdfService.resolvePdf(authoringSession.syllabusVersion()).orElse(null);
		if (managedPdf == null) {
			return;
		}
		if (!Files.isRegularFile(managedPdf)) {
			pdfPathLabel.setText(managedPdf.toString());
			statusLabel.setText("Managed syllabus PDF is unavailable.");
			return;
		}
		openSyllabusPdf(managedPdf);
	}

	private String parentInstruction(CurriculumLevel level) {
		return switch (level) {
		case UNIT -> "Units do not require a parent.";
		case TOPIC -> "Select the Unit that will contain this Topic.";
		case SUBTOPIC -> "Select the Topic that will contain this Subtopic.";
		case DESCRIPTOR -> "Select the Topic or Subtopic that will contain this Descriptor.";
		};
	}

	private void rebuildTree(Long selectedDraftId) {
		changingTree = true;
		try {
			rebuildTreeItems(selectedDraftId);
		} finally {
			changingTree = false;
			refreshSelectedNodeDetails();
		}
	}

	private void rebuildTreeItems(Long selectedDraftId) {
		Set<Long> collapsedDraftIds = collapsedDraftIds();
		treeRoot.getChildren().clear();
		Set<Long> visited = new HashSet<>();
		for (CurriculumDraftNode rootNode : draft.childrenOf(null)) {
			treeRoot.getChildren().add(buildTreeItem(rootNode, visited, collapsedDraftIds));
		}

		// A malformed draft should never silently disappear from the UI. Show any
		// unreachable nodes at root level for correction.
		for (CurriculumDraftNode node : draft.nodes()) {
			if (!visited.contains(node.draftId())) {
				treeRoot.getChildren().add(buildTreeItem(node, visited, collapsedDraftIds));
			}
		}
		if (selectedDraftId == null) {
			draftTree.getSelectionModel().clearSelection();
			return;
		}
		TreeItem<CurriculumDraftNode> item = findTreeItem(treeRoot, selectedDraftId);
		if (item != null) {
			draftTree.getSelectionModel().select(item);
			draftTree.scrollTo(draftTree.getRow(item));
		} else {
			draftTree.getSelectionModel().clearSelection();
		}
	}

	private void refreshDirtyLabel() {
		if (isPersistentMode() && isEditable()) {
			lifecycleLabel.setText(hasUnsavedChanges() ? "IN_PROGRESS — unsaved changes" : "IN_PROGRESS");
		}
	}

	private void refreshLifecycleState() {
		if (!isPersistentMode()) {
			lifecycleLabel.setText("Preview — changes are not persisted.");
			saveButton.setDisable(true);
			finaliseButton.setDisable(true);
			reopenButton.setDisable(true);
			browseButton.setDisable(false);
			refreshSelectedNodeDetails();
			return;
		}
		if (authoringSession.syllabusVersion().isCurriculumFinal()) {
			String finalisedAt = authoringSession.syllabusVersion().getCurriculumFinalisedAt().toString();
			lifecycleLabel.setText("FINAL — " + finalisedAt);
			saveButton.setDisable(true);
			finaliseButton.setDisable(true);
			reopenButton.setDisable(false);
			browseButton.setDisable(true);
		} else {
			refreshDirtyLabel();
			saveButton.setDisable(false);
			finaliseButton.setDisable(false);
			reopenButton.setDisable(true);
			browseButton.setDisable(false);
		}
		refreshSelectedNodeDetails();
	}

	private void refreshPageText() {
		try {
			pageTextArea.setText(pdfWorkspace.extractDisplayedPageText());
			pageTextArea.positionCaret(0);
		} catch (IllegalStateException e) {
			pageTextArea.clear();
		} catch (IOException e) {
			pageTextArea.clear();
			statusLabel.setText("Unable to extract text from this PDF page.");
		}
	}

	private void refreshSelectedNodeDetails() {
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			selectedLevelValue.setText("-");
			selectedCodeValue.setText("-");
			selectedPageValue.setText("-");
			editorDraftId = null;
			editTextArea.clear();
			editTextArea.setDisable(true);
			contextLabel.setText("No parent selected. Add Unit is available.");
			updateTextButton.setDisable(true);
			deleteButton.setDisable(true);
			moveUpButton.setDisable(true);
			moveDownButton.setDisable(true);
			updateCaptureButtonState(null);
			return;
		}
		selectedLevelValue.setText(selected.level().toString());
		selectedCodeValue.setText(selected.code());
		selectedPageValue.setText(selected.sourcePageNumber() == null ? "-" : selected.sourcePageNumber().toString());
		editTextArea.setDisable(!isEditable());
		if (!Objects.equals(editorDraftId, selected.draftId())) {
			editorDraftId = selected.draftId();
			editTextArea.setText(selected.name());
		}
		contextLabel.setText(
				"Current parent context: " + selected.code() + " " + selected.level() + " — " + selected.name());
		updateTextButton.setDisable(!isEditable());
		deleteButton.setDisable(!isEditable());
		updateCaptureButtonState(selected);
		updateMoveButtonState(selected);
	}

	private void refreshValidation() {
		List<String> problems = draft.validationProblems();
		if (problems.isEmpty()) {
			validationArea.setText("Draft is valid.");
		} else {
			validationArea.setText(String.join(System.lineSeparator(), problems));
		}
		validationArea.positionCaret(0);
	}

	private void reopenFromUi() {
		try {
			reopenCurriculum();
		} catch (IllegalStateException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private void requirePersistentMode() {
		if (!isPersistentMode()) {
			throw new IllegalStateException("This curriculum authoring window is preview-only");
		}
	}

	private void saveFromUi() {
		try {
			saveCurriculum();
		} catch (IllegalArgumentException | IllegalStateException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private CurriculumDraftNode selectedNode() {
		TreeItem<CurriculumDraftNode> selected = draftTree.getSelectionModel().getSelectedItem();
		return selected == null ? null : selected.getValue();
	}

	private void updateCaptureButtonState(CurriculumDraftNode selected) {
		if (!isEditable()) {
			addUnitButton.setDisable(true);
			addTopicButton.setDisable(true);
			addSubtopicButton.setDisable(true);
			addDescriptorButton.setDisable(true);
			return;
		}
		boolean usesDirectTopicDescriptors = usesDirectTopicDescriptors();
		boolean usesSubtopics = usesSubtopics();
		addUnitButton.setDisable(false);
		addTopicButton.setDisable(selected == null || selected.level() != CurriculumLevel.UNIT);
		addSubtopicButton.setDisable(
				selected == null || selected.level() != CurriculumLevel.TOPIC || usesDirectTopicDescriptors);
		if (selected == null) {
			addDescriptorButton.setDisable(true);
			return;
		}
		if (selected.level() == CurriculumLevel.SUBTOPIC) {
			addDescriptorButton.setDisable(false);
			return;
		}
		if (selected.level() == CurriculumLevel.TOPIC) {
			addDescriptorButton.setDisable(usesSubtopics);
			return;
		}
		addDescriptorButton.setDisable(true);
	}

	private void updateMoveButtonState(CurriculumDraftNode selected) {
		if (!isEditable()) {
			moveUpButton.setDisable(true);
			moveDownButton.setDisable(true);
			return;
		}
		List<CurriculumDraftNode> siblings = draft.childrenOf(selected.parentDraftId());
		int index = -1;
		for (int candidate = 0; candidate < siblings.size(); candidate++) {
			if (siblings.get(candidate).draftId() == selected.draftId()) {
				index = candidate;
				break;
			}
		}
		moveUpButton.setDisable(index <= 0);
		moveDownButton.setDisable(index < 0 || index >= siblings.size() - 1);
	}

	private void updateSelectedText() {
		if (!isEditable()) {
			statusLabel.setText("Reopen the curriculum before editing.");
			return;
		}
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			return;
		}
		try {
			applyPendingEditorText();
			refreshSelectedNodeDetails();
			statusLabel.setText("Updated " + currentNode(selected.draftId()).code() + ".");
		} catch (IllegalArgumentException | NullPointerException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private boolean usesDirectTopicDescriptors() {
		for (CurriculumDraftNode node : draft.nodes()) {
			if (node.level() != CurriculumLevel.DESCRIPTOR || node.parentDraftId() == null) {
				continue;
			}
			CurriculumDraftNode parent = draft.findNode(node.parentDraftId()).orElse(null);
			if (parent != null && parent.level() == CurriculumLevel.TOPIC) {
				return true;
			}
		}
		return false;
	}

	private boolean usesSubtopics() {
		return draft.nodes().stream().anyMatch(node -> node.level() == CurriculumLevel.SUBTOPIC);
	}
}
