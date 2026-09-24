package au.edu.eq.questionbank.ui.exam;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import au.edu.eq.questionbank.importer.legacy.LegacyBookletRequirement;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Collects optional answer or marking-guide PDFs for legacy exams whose
 * Question booklets are already present.
 */
public final class LegacyAnswerPdfImportDialog extends Dialog<ButtonType> {

	private static final int VIEWPORT_WIDTH = 620;
	private static final int VIEWPORT_HEIGHT = 320;
	private static final int SECTION_SPACING = 12;
	private static final int CONTENT_PADDING = 10;
	private static final int PDF_LABEL_WIDTH = 300;
	private static final int ROW_SPACING = 8;
	private final List<ExamKey> exams;
	private final Map<ExamKey, Path> selectedPdfs = new LinkedHashMap<>();
	private final Map<ExamKey, Label> pdfLabels = new LinkedHashMap<>();
	private Path lastPdfDirectory;

	/**
	 * Creates the selection dialog for the distinct exams in the requirements.
	 *
	 * @param owner        owner window
	 * @param requirements legacy booklet requirements identifying the exams
	 */
	public LegacyAnswerPdfImportDialog(Window owner, List<LegacyBookletRequirement> requirements) {
		if (requirements == null) {
			throw new NullPointerException("requirements");
		}
		if (requirements.isEmpty()) {
			throw new IllegalArgumentException("requirements must not be empty");
		}
		Set<ExamKey> distinctExams = new LinkedHashSet<>();
		for (LegacyBookletRequirement requirement : requirements) {
			distinctExams.add(new ExamKey(requirement.providerName(), requirement.year()));
		}
		exams = List.copyOf(distinctExams);
		initOwner(owner);
		setTitle("Import Answer PDFs");
		setHeaderText("All required exam booklets already exist. " + "Optionally select an answer or marking-guide PDF "
				+ "for each exam.");
		ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
		getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);
		ScrollPane scrollPane = new ScrollPane(createContent(owner));
		scrollPane.setFitToWidth(true);
		scrollPane.setPrefViewportWidth(VIEWPORT_WIDTH);
		scrollPane.setPrefViewportHeight(VIEWPORT_HEIGHT);
		getDialogPane().setContent(scrollPane);
	}

	/**
	 * Returns selections for exams given an answer PDF.
	 *
	 * @return immutable selections in first-requirement order
	 */
	public List<AnswerPdfSelection> getSelections() {
		List<AnswerPdfSelection> selections = new ArrayList<>();
		for (ExamKey exam : exams) {
			Path pdfPath = selectedPdfs.get(exam);
			if (pdfPath != null) {
				selections.add(new AnswerPdfSelection(exam.providerName(), exam.year(), pdfPath));
			}
		}
		return List.copyOf(selections);
	}

	private void choosePdf(Window owner, ExamKey exam) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Select " + exam.providerName() + " " + exam.year() + " answer or marking guide");
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
		selectedPdfs.put(exam, path);
		pdfLabels.get(exam).setText(file.getName());
	}

	private VBox createContent(Window owner) {
		VBox content = new VBox(SECTION_SPACING);
		content.setPadding(new Insets(CONTENT_PADDING));
		for (ExamKey exam : exams) {
			Label heading = new Label(exam.providerName() + " " + exam.year());
			heading.setStyle("-fx-font-weight: bold;");
			Label pdfLabel = new Label("No answer PDF selected");
			pdfLabel.setPrefWidth(PDF_LABEL_WIDTH);
			pdfLabels.put(exam, pdfLabel);
			Button chooseButton = new Button("Choose Answer PDF...");
			chooseButton.setOnAction(_ -> choosePdf(owner, exam));
			HBox row = new HBox(ROW_SPACING, pdfLabel, chooseButton);
			content.getChildren().addAll(heading, row);
		}
		return content;
	}

	/**
	 * Selected answer document for one provider and year.
	 *
	 * @param providerName exam-provider name
	 * @param year         exam year
	 * @param pdfPath      selected source PDF
	 */
	public record AnswerPdfSelection(String providerName, int year, Path pdfPath) {
	}

	private record ExamKey(String providerName, int year) {
	}
}
