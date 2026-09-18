package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.importer.legacy.LegacyBookletImportRequest;
import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class LegacyBookletImportDialog extends Dialog<ButtonType> {

	private static final int VIEWPORT_WIDTH = 680;
	private static final int VIEWPORT_HEIGHT = 500;
	private static final int SECTION_SPACING = 12;
	private static final int CONTENT_PADDING = 10;
	private static final int EXAM_FIELD_WIDTH = 300;
	private static final int ROW_SPACING = 8;
	private static final int BOOKLET_LABEL_WIDTH = 140;
	private static final int PDF_LABEL_WIDTH = 260;

	private final List<LegacyBookletRequirement> requirements;
	private final Map<ExamKey, TextField> assessmentFields = new LinkedHashMap<>();
	private final Map<ExamKey, Path> selectedAnswerPdfs = new LinkedHashMap<>();
	private final Map<ExamKey, Label> answerPdfLabels = new LinkedHashMap<>();
	private final Map<LegacyBookletRequirement, Path> selectedPdfs = new LinkedHashMap<>();
	private final Map<LegacyBookletRequirement, Label> pdfLabels = new LinkedHashMap<>();
	private Path lastPdfDirectory;

	LegacyBookletImportDialog(Window owner, List<LegacyBookletRequirement> requirements) {
		if (requirements == null) {
			throw new NullPointerException("requirements");
		}
		if (requirements.isEmpty()) {
			throw new IllegalArgumentException("requirements must not be empty");
		}
		this.requirements = List.copyOf(requirements);
		initOwner(owner);
		setTitle("Import Missing Exam Booklets");
		setHeaderText("Select the source PDFs required by the legacy question metadata.");
		ButtonType importButtonType = new ButtonType("Import Booklets", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
		VBox content = createContent(owner);
		ScrollPane scrollPane = new ScrollPane(content);
		scrollPane.setFitToWidth(true);
		scrollPane.setPrefViewportWidth(VIEWPORT_WIDTH);
		scrollPane.setPrefViewportHeight(VIEWPORT_HEIGHT);
		getDialogPane().setContent(scrollPane);
		Button importButton = (Button) getDialogPane().lookupButton(importButtonType);
		importButton.addEventFilter(javafx.event.ActionEvent.ACTION, this::validateImportAction);
	}

	List<LegacyBookletImportRequest> getRequests() {
		List<LegacyBookletImportRequest> requests = new ArrayList<>();
		for (LegacyBookletRequirement requirement : requirements) {
			ExamKey key = new ExamKey(requirement.providerName(), requirement.year());
			String assessmentName = assessmentFields.get(key).getText().trim();
			requests.add(new LegacyBookletImportRequest(requirement, assessmentName, selectedPdfs.get(requirement),
					selectedAnswerPdfs.get(key)));
		}
		return List.copyOf(requests);
	}

	private void chooseAnswerPdf(Window owner, ExamKey key) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select " + key.providerName() + " " + key.year() + " answer or marking guide");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
		if (lastPdfDirectory != null && lastPdfDirectory.toFile().isDirectory()) {
			chooser.setInitialDirectory(lastPdfDirectory.toFile());
		}
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		Path path = file.toPath().toAbsolutePath().normalize();
		lastPdfDirectory = path.getParent();
		selectedAnswerPdfs.put(key, path);
		answerPdfLabels.get(key).setText(file.getName());
	}

	private void choosePdf(Window owner, LegacyBookletRequirement requirement) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(
				"Select " + requirement.providerName() + " " + requirement.year() + " " + requirement.bookletName());
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
		if (lastPdfDirectory != null && lastPdfDirectory.toFile().isDirectory()) {
			chooser.setInitialDirectory(lastPdfDirectory.toFile());
		}
		File file = chooser.showOpenDialog(owner);
		if (file == null) {
			return;
		}
		Path path = file.toPath().toAbsolutePath().normalize();
		lastPdfDirectory = path.getParent();
		selectedPdfs.put(requirement, path);
		pdfLabels.get(requirement).setText(file.getName());
	}

	private VBox createContent(Window owner) {
		VBox content = new VBox(SECTION_SPACING);
		content.setPadding(new Insets(CONTENT_PADDING));
		ExamKey previousKey = null;
		for (LegacyBookletRequirement requirement : requirements) {
			ExamKey key = new ExamKey(requirement.providerName(), requirement.year());
			if (!key.equals(previousKey)) {
				Label heading = new Label(requirement.providerName() + " " + requirement.year());
				heading.setStyle("-fx-font-weight: bold;");
				TextField assessmentField = new TextField();
				assessmentField.setPromptText("e.g. External Assessment");
				assessmentField.setPrefWidth(EXAM_FIELD_WIDTH);
				assessmentFields.put(key, assessmentField);
				HBox assessmentRow = new HBox(ROW_SPACING, new Label("Assessment:"), assessmentField);
				Label answerPdfLabel = new Label("No answer PDF selected");
				answerPdfLabel.setPrefWidth(EXAM_FIELD_WIDTH);
				answerPdfLabels.put(key, answerPdfLabel);
				Button chooseAnswerButton = new Button("Choose Answer PDF...");
				chooseAnswerButton.setOnAction(_ -> chooseAnswerPdf(owner, key));
				HBox answerRow = new HBox(ROW_SPACING, new Label("Answer PDF:"), answerPdfLabel, chooseAnswerButton);
				content.getChildren().addAll(heading, assessmentRow, answerRow);
				previousKey = key;
			}
			Label bookletLabel = new Label(requirement.bookletName());
			bookletLabel.setPrefWidth(BOOKLET_LABEL_WIDTH);
			Label pdfLabel = new Label("No PDF selected");
			pdfLabel.setPrefWidth(PDF_LABEL_WIDTH);
			pdfLabels.put(requirement, pdfLabel);
			Button chooseButton = new Button("Choose PDF...");
			chooseButton.setOnAction(_ -> choosePdf(owner, requirement));
			HBox bookletRow = new HBox(ROW_SPACING, bookletLabel, pdfLabel, chooseButton);
			content.getChildren().add(bookletRow);
		}
		return content;
	}

	private boolean isValid() {
		for (TextField assessmentField : assessmentFields.values()) {
			if (assessmentField.getText().isBlank()) {
				assessmentField.requestFocus();
				return false;
			}
		}
		for (LegacyBookletRequirement requirement : requirements) {
			if (!selectedPdfs.containsKey(requirement)) {
				return false;
			}
		}
		return true;
	}

	private void validateImportAction(javafx.event.ActionEvent event) {
		if (!isValid()) {
			event.consume();
		}
	}

	private record ExamKey(String providerName, int year) {
	}
}
