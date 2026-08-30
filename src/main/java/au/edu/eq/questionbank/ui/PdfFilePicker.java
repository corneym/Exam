package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Chooses PDFs and rejects selections outside the configured PDF data root.
 */
final class PdfFilePicker {

	private final Path dataRoot;

	/**
	 * Creates a picker restricted to one data root.
	 *
	 * @param dataRoot the configured PDF data root
	 */
	PdfFilePicker(Path dataRoot) {
		if (dataRoot == null) {
			throw new NullPointerException("dataRoot");
		}
		this.dataRoot = dataRoot.toAbsolutePath().normalize();
	}

	/**
	 * Shows a PDF chooser and validates the selected path.
	 *
	 * @param stage              the owner stage
	 * @param title              the chooser title
	 * @param invalidPathMessage alert heading used for an out-of-root selection
	 * @return the selected PDF, or {@code null} when cancelled or rejected
	 */
	SelectedPdf choose(Stage stage, String title, String invalidPathMessage) {
		FileChooser chooser = createFileChooser(title);
		File selectedFile = chooser.showOpenDialog(stage);
		if (selectedFile == null) {
			return null;
		}

		Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();
		if (!isInsideDataRoot(selectedPath)) {
			showInvalidPathAlert(invalidPathMessage);
			return null;
		}

		return new SelectedPdf(selectedFile, selectedPath, dataRoot);
	}

	/**
	 * Tests whether a path resolves within the configured data root.
	 *
	 * @param path the path to test
	 * @return {@code true} when the normalised absolute path is contained
	 */
	boolean isInsideDataRoot(Path path) {
		return path.toAbsolutePath().normalize().startsWith(dataRoot);
	}

	private FileChooser createFileChooser(String title) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(title);
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF files", "*.pdf"));
		File root = dataRoot.toFile();
		if (root.isDirectory()) {
			chooser.setInitialDirectory(root);
		}
		return chooser;
	}

	private void showInvalidPathAlert(String invalidPathMessage) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText(invalidPathMessage);
		alert.setContentText(dataRoot.toString());
		alert.showAndWait();
	}
}
