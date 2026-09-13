package au.edu.eq.questionbank.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNumberingService;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftTextExporter;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * PDF-assisted workspace for explicitly authoring a curriculum hierarchy.
 * <p>
 * The user selects syllabus text, explicitly chooses the node type, and
 * explicitly establishes parent context by selecting a node in the draft tree.
 * Numeric codes are derived from that authored hierarchy.
 */
final class CurriculumAuthoringPane extends BorderPane implements AutoCloseable {

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
	private final Button exportButton = new Button("Export draft...");
	private final TextArea validationArea = new TextArea();
	private final Label statusLabel = new Label();
	private Path syllabusPdfPath;

	CurriculumAuthoringPane(Stage ownerStage, Path curriculumDataRoot, CurriculumDraft draft) {
		if (ownerStage == null) {
			throw new NullPointerException("ownerStage");
		}
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		if (draft == null) {
			throw new NullPointerException("draft");
		}
		this.ownerStage = ownerStage;
		this.pdfFilePicker = new PdfFilePicker(curriculumDataRoot);
		this.draft = draft;
		configurePdfArea();
		configureTree();
		configureCaptureButtons();
		configureCorrectionControls();
		configureLayout();
		rebuildTree(null);
		refreshValidation();
		refreshSelectedNodeDetails();
	}

	@Override
	public void close() throws Exception {
		pdfWorkspace.close();
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

	private void browseForPdf() {
		Path selected = pdfFilePicker.chooseAnyPdf(ownerStage, "Select Curriculum Syllabus PDF");
		if (selected != null) {
			openSyllabusPdf(selected);
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
		Long parentDraftId = selectedParent == null ? null : selectedParent.draftId();
		try {
			CurriculumDraftNode added = numbering.addNode(draft, level, selectedText.strip(), parentDraftId,
					pdfWorkspace.getCurrentPageNumber());
			Long selectionAfterCapture;
			if (level == CurriculumLevel.UNIT) {
				/*
				 * A new Unit becomes the current context so topics can be captured immediately.
				 */
				selectionAfterCapture = added.draftId();
			} else {
				/*
				 * Keep the existing parent selected. This is important for capturing several
				 * siblings in succession.
				 */
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

	private void chooseAndExportDraft() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Export Curriculum Draft");
		chooser.setInitialFileName("curriculum-draft.txt");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text files", "*.txt"));
		File initialDirectory = pdfFilePicker.dataRoot().toFile();
		if (initialDirectory.isDirectory()) {
			chooser.setInitialDirectory(initialDirectory);
		}
		File selectedFile = chooser.showSaveDialog(ownerStage);
		if (selectedFile == null) {
			return;
		}
		Path target = selectedFile.toPath().toAbsolutePath().normalize();
		try {
			exportDraft(target);
			statusLabel.setText("Draft exported to " + target + ".");
		} catch (IOException e) {
			statusLabel.setText("Unable to export draft: " + e.getMessage());
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
		editTextArea.setPrefRowCount(4);
		updateTextButton.setId("update-curriculum-text");
		updateTextButton.setOnAction(_ -> updateSelectedText());
		deleteButton.setId("delete-curriculum-node");
		deleteButton.setOnAction(_ -> confirmAndDeleteSelectedNode());
		moveUpButton.setId("move-curriculum-node-up");
		moveUpButton.setOnAction(_ -> moveSelectedNode(true));
		moveDownButton.setId("move-curriculum-node-down");
		moveDownButton.setOnAction(_ -> moveSelectedNode(false));
		exportButton.setId("export-curriculum-draft");
		exportButton.setOnAction(_ -> chooseAndExportDraft());
		validationArea.setId("curriculum-validation");
		validationArea.setEditable(false);
		validationArea.setWrapText(true);
		validationArea.setPrefRowCount(3);
		statusLabel.setId("curriculum-authoring-status");
		statusLabel.setWrapText(true);
		contextLabel.setWrapText(true);
	}

	private void configureLayout() {
		Button browseButton = new Button("Browse...");
		browseButton.setId("browse-syllabus-pdf");
		browseButton.setOnAction(_ -> browseForPdf());
		HBox pdfHeader = new HBox(8, new Label("Syllabus PDF:"), pdfPathLabel, browseButton);
		pdfHeader.setPadding(new Insets(8));
		Label captureInstruction = new Label(
				"Highlight wording below, select its parent in the curriculum tree, " + "then choose the node type.");
		captureInstruction.setWrapText(true);
		HBox captureButtons = new HBox(6, addUnitButton, addTopicButton, addSubtopicButton, addDescriptorButton);
		VBox textCaptureBox = new VBox(6, new Label("Current page text"), captureInstruction, pageTextArea,
				captureButtons, contextLabel);
		textCaptureBox.setPadding(new Insets(8));
		VBox.setVgrow(pageTextArea, Priority.ALWAYS);
		/*
		 * The PDF is reference material. Give roughly half of the left-hand workspace
		 * to the PDF and half to extracted/selectable text.
		 */
		SplitPane sourceSplit = new SplitPane(pdfWorkspace, textCaptureBox);
		sourceSplit.setOrientation(Orientation.VERTICAL);
		sourceSplit.setDividerPositions(0.48);
		GridPane selectedDetails = new GridPane();
		selectedDetails.setHgap(8);
		selectedDetails.setVgap(6);
		selectedDetails.add(new Label("Level:"), 0, 0);
		selectedDetails.add(selectedLevelValue, 1, 0);
		selectedDetails.add(new Label("Code:"), 0, 1);
		selectedDetails.add(selectedCodeValue, 1, 1);
		selectedDetails.add(new Label("Source page:"), 0, 2);
		selectedDetails.add(selectedPageValue, 1, 2);
		HBox correctionButtons = new HBox(6, updateTextButton, moveUpButton, moveDownButton, deleteButton);
		VBox treeBox = new VBox(8, new Label("Draft curriculum"), draftTree, new Label("Selected node"),
				selectedDetails, editTextArea, correctionButtons, new Label("Validation"), validationArea, exportButton,
				statusLabel);
		treeBox.setPadding(new Insets(10));
		VBox.setVgrow(draftTree, Priority.ALWAYS);
		/*
		 * The curriculum tree is now the complete right-hand working pane.
		 */
		SplitPane workspaceSplit = new SplitPane(sourceSplit, treeBox);
		workspaceSplit.setDividerPositions(0.50);
		setTop(pdfHeader);
		setCenter(workspaceSplit);
	}

	private void configurePdfArea() {
		pageTextArea.setId("syllabus-page-text");
		pageTextArea.setEditable(false);
		pageTextArea.setWrapText(true);
		pageTextArea.setPrefRowCount(18);
		pageTextArea.setMinHeight(300);
		pdfWorkspace.setSelectionAvailable(_ -> false);
		pdfWorkspace.setPageChangedHandler(_ -> refreshPageText());
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
		draftTree.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> refreshSelectedNodeDetails());
	}

	private void confirmAndDeleteSelectedNode() {
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
		rebuildTree(null);
		refreshValidation();
		statusLabel.setText("Deleted " + removed.size() + (removed.size() == 1 ? " node." : " nodes."));
	}

	private CurriculumDraftNode currentNode(long draftId) {
		return draft.findNode(draftId).orElseThrow();
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

	private boolean isPermittedParent(CurriculumDraftNode parent, CurriculumLevel childLevel) {
		return switch (childLevel) {
		case UNIT -> true;
		case TOPIC -> parent != null && parent.level() == CurriculumLevel.UNIT;
		case SUBTOPIC -> parent != null && parent.level() == CurriculumLevel.TOPIC;
		case DESCRIPTOR ->
			parent != null && (parent.level() == CurriculumLevel.TOPIC || parent.level() == CurriculumLevel.SUBTOPIC);
		};
	}

	private void moveSelectedNode(boolean upward) {
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			return;
		}
		long draftId = selected.draftId();
		boolean moved = upward ? numbering.moveUp(draft, draftId) : numbering.moveDown(draft, draftId);
		if (!moved) {
			return;
		}
		rebuildTree(draftId);
		refreshValidation();
		statusLabel.setText("Moved " + currentNode(draftId).code() + ".");
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
		Set<Long> collapsedDraftIds = collapsedDraftIds();
		treeRoot.getChildren().clear();
		Set<Long> visited = new HashSet<>();
		for (CurriculumDraftNode rootNode : draft.childrenOf(null)) {
			treeRoot.getChildren().add(buildTreeItem(rootNode, visited, collapsedDraftIds));
		}
		/*
		 * A malformed draft should never silently disappear from the UI. Show any
		 * unreachable nodes at root level for correction.
		 */
		for (CurriculumDraftNode node : draft.nodes()) {
			if (!visited.contains(node.draftId())) {
				treeRoot.getChildren().add(buildTreeItem(node, visited, collapsedDraftIds));
			}
		}
		if (selectedDraftId == null) {
			draftTree.getSelectionModel().clearSelection();
			refreshSelectedNodeDetails();
			return;
		}
		TreeItem<CurriculumDraftNode> item = findTreeItem(treeRoot, selectedDraftId);
		if (item != null) {
			draftTree.getSelectionModel().select(item);
			draftTree.scrollTo(draftTree.getRow(item));
		} else {
			draftTree.getSelectionModel().clearSelection();
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
		editTextArea.setDisable(false);
		editTextArea.setText(selected.name());
		contextLabel.setText(
				"Current parent context: " + selected.code() + " " + selected.level() + " — " + selected.name());
		updateTextButton.setDisable(false);
		deleteButton.setDisable(false);
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

	private CurriculumDraftNode selectedNode() {
		TreeItem<CurriculumDraftNode> selected = draftTree.getSelectionModel().getSelectedItem();
		return selected == null ? null : selected.getValue();
	}

	private void updateCaptureButtonState(CurriculumDraftNode selected) {
		addUnitButton.setDisable(false);
		addTopicButton.setDisable(selected == null || selected.level() != CurriculumLevel.UNIT);
		addSubtopicButton.setDisable(selected == null || selected.level() != CurriculumLevel.TOPIC);
		addDescriptorButton.setDisable(selected == null
				|| (selected.level() != CurriculumLevel.TOPIC && selected.level() != CurriculumLevel.SUBTOPIC));
	}

	private void updateMoveButtonState(CurriculumDraftNode selected) {
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
		CurriculumDraftNode selected = selectedNode();
		if (selected == null) {
			return;
		}
		try {
			CurriculumDraftNode updated = numbering.updateText(draft, selected.draftId(),
					editTextArea.getText().strip());
			rebuildTree(updated.draftId());
			refreshValidation();
			statusLabel.setText("Updated " + currentNode(updated.draftId()).code() + ".");
		} catch (IllegalArgumentException | NullPointerException e) {
			statusLabel.setText(e.getMessage());
		}
	}
}
