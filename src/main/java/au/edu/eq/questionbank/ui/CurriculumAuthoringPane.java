package au.edu.eq.questionbank.ui;

import java.io.IOException;
import java.nio.file.Path;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraft;
import au.edu.eq.questionbank.service.curriculum.CurriculumDraftNode;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Workspace for manually authoring a curriculum while referring to an
 * authoritative syllabus PDF.
 * <p>
 * PDF text assists data entry only. Curriculum level and parent relationships
 * remain explicit user decisions.
 */
final class CurriculumAuthoringPane extends BorderPane implements AutoCloseable {

	private final Stage ownerStage;
	private final PdfFilePicker pdfFilePicker;
	private final CurriculumDraft draft;
	private final PdfWorkspacePane pdfWorkspace = new PdfWorkspacePane();
	private final Label pdfPathLabel = new Label("No syllabus PDF selected");
	private final TextArea pageTextArea = new TextArea();
	private final ComboBox<CurriculumLevel> levelBox = new ComboBox<>();
	private final TextField codeField = new TextField();
	private final TextArea nodeTextArea = new TextArea();
	private final ComboBox<CurriculumDraftNode> parentBox = new ComboBox<>();
	private final ListView<CurriculumDraftNode> draftList = new ListView<>();
	private final Label statusLabel = new Label();
	private Path syllabusPdfPath;

	/**
	 * Creates a curriculum-authoring workspace.
	 *
	 * @param ownerStage         owner used by the PDF chooser
	 * @param curriculumDataRoot initial directory for syllabus selection
	 * @param draft              transient curriculum being authored
	 */
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
		configureAuthoringControls();
		configureLayout();
		refreshDraftViews();
	}

	/**
	 * Closes the owned PDF workspace.
	 */
	@Override
	public void close() throws Exception {
		pdfWorkspace.close();
	}

	/**
	 * Opens a syllabus PDF for authoring.
	 *
	 * @param path syllabus PDF path
	 */
	void openSyllabusPdf(Path path) {
		if (path == null) {
			throw new NullPointerException("path");
		}
		Path normalisedPath = path.toAbsolutePath().normalize();
		pdfWorkspace.openViewerPdf(normalisedPath);
		syllabusPdfPath = normalisedPath;
		pdfPathLabel.setText(normalisedPath.toString());
		refreshPageText();
	}

	private void addNode() {
		if (syllabusPdfPath == null) {
			statusLabel.setText("Select a syllabus PDF first.");
			return;
		}
		CurriculumLevel level = levelBox.getValue();
		CurriculumDraftNode parent = parentBox.getValue();
		Long parentDraftId = parent == null ? null : parent.draftId();
		try {
			CurriculumDraftNode added = draft.addNode(level, codeField.getText().strip(),
					nodeTextArea.getText().strip(), parentDraftId, pdfWorkspace.getCurrentPageNumber());
			refreshDraftViews();
			draftList.getSelectionModel().select(added);
			codeField.clear();
			nodeTextArea.clear();
			statusLabel.setText("Added " + added.code() + " from syllabus page " + added.sourcePageNumber() + ".");
		} catch (IllegalArgumentException | NullPointerException e) {
			statusLabel.setText(e.getMessage());
		}
	}

	private void browseForPdf() {
		Path selected = pdfFilePicker.chooseAnyPdf(ownerStage, "Select Curriculum Syllabus PDF");
		if (selected != null) {
			openSyllabusPdf(selected);
		}
	}

	private void configureAuthoringControls() {
		levelBox.setId("curriculum-level");
		levelBox.getItems().setAll(CurriculumLevel.values());
		levelBox.setValue(CurriculumLevel.UNIT);
		levelBox.setOnAction(_ -> updateParentControl());
		codeField.setId("curriculum-code");
		nodeTextArea.setId("curriculum-node-text");
		nodeTextArea.setWrapText(true);
		nodeTextArea.setPrefRowCount(4);
		parentBox.setId("curriculum-parent");
		parentBox.setConverter(new StringConverter<>() {

			@Override
			public CurriculumDraftNode fromString(String value) {
				return null;
			}

			@Override
			public String toString(CurriculumDraftNode node) {
				return node == null ? "" : node.level() + " " + node.code();
			}
		});
		draftList.setId("curriculum-draft-list");
		draftList.setCellFactory(_ -> new ListCell<>() {

			@Override
			protected void updateItem(CurriculumDraftNode node, boolean empty) {
				super.updateItem(node, empty);
				setText(empty || node == null ? null : node.level() + "  " + node.code() + " — " + node.name());
			}
		});
		statusLabel.setId("curriculum-authoring-status");
		updateParentControl();
	}

	private void configureLayout() {
		Button browseButton = new Button("Browse...");
		browseButton.setId("browse-syllabus-pdf");
		browseButton.setOnAction(_ -> browseForPdf());
		HBox pdfHeader = new HBox(8, new Label("Syllabus PDF:"), pdfPathLabel, browseButton);
		pdfHeader.setPadding(new Insets(8));
		Button useSelectedTextButton = new Button("Use selected text");
		useSelectedTextButton.setId("use-selected-syllabus-text");
		useSelectedTextButton.setOnAction(_ -> useSelectedPageText());
		VBox textBox = new VBox(6, new Label("Current page text"), pageTextArea, useSelectedTextButton);
		VBox.setVgrow(pageTextArea, Priority.ALWAYS);
		GridPane editorGrid = new GridPane();
		editorGrid.setHgap(8);
		editorGrid.setVgap(8);
		editorGrid.add(new Label("Level:"), 0, 0);
		editorGrid.add(levelBox, 1, 0);
		editorGrid.add(new Label("Code:"), 0, 1);
		editorGrid.add(codeField, 1, 1);
		editorGrid.add(new Label("Parent:"), 0, 2);
		editorGrid.add(parentBox, 1, 2);
		editorGrid.add(new Label("Text:"), 0, 3);
		editorGrid.add(nodeTextArea, 1, 3);
		Button addButton = new Button("Add node");
		addButton.setId("add-curriculum-node");
		addButton.setOnAction(_ -> addNode());
		VBox editorBox = new VBox(10, textBox, editorGrid, addButton, statusLabel, new Label("Draft curriculum"),
				draftList);
		editorBox.setPadding(new Insets(10));
		VBox.setVgrow(textBox, Priority.ALWAYS);
		VBox.setVgrow(draftList, Priority.ALWAYS);
		SplitPane splitPane = new SplitPane(pdfWorkspace, editorBox);
		splitPane.setDividerPositions(0.55);
		setTop(pdfHeader);
		setCenter(splitPane);
	}

	private void configurePdfArea() {
		pageTextArea.setId("syllabus-page-text");
		pageTextArea.setEditable(false);
		pageTextArea.setWrapText(true);
		pdfWorkspace.setSelectionAvailable(_ -> false);
		pdfWorkspace.setPageChangedHandler(_ -> refreshPageText());
	}

	private void refreshDraftViews() {
		Long selectedParentId = parentBox.getValue() == null ? null : parentBox.getValue().draftId();
		draftList.getItems().setAll(draft.nodes());
		parentBox.getItems().setAll(draft.nodes());
		if (selectedParentId != null) {
			draft.findNode(selectedParentId).ifPresent(node -> parentBox.setValue(node));
		}
	}

	private void refreshPageText() {
		try {
			pageTextArea.setText(pdfWorkspace.extractDisplayedPageText());
			pageTextArea.positionCaret(0);
			statusLabel.setText("");
		} catch (IllegalStateException e) {
			pageTextArea.clear();
		} catch (IOException e) {
			pageTextArea.clear();
			statusLabel.setText("Unable to extract text from this PDF page.");
		}
	}

	private void updateParentControl() {
		boolean unitSelected = levelBox.getValue() == CurriculumLevel.UNIT;
		if (unitSelected) {
			parentBox.setValue(null);
		}
		parentBox.setDisable(unitSelected);
	}

	private void useSelectedPageText() {
		String selectedText = pageTextArea.getSelectedText();
		if (selectedText == null || selectedText.isBlank()) {
			statusLabel.setText("Select syllabus text first.");
			return;
		}
		nodeTextArea.setText(selectedText.strip());
		statusLabel.setText("");
	}
}
