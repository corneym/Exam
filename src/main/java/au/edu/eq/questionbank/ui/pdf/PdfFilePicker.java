package au.edu.eq.questionbank.ui.pdf;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiFunction;

import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Chooses PDFs and rejects selections outside the configured PDF data root.
 */
public final class PdfFilePicker {

	// PDF selection configuration and location.
	private final BiFunction<FileChooser, Stage, File> chooserOpener;
	private final Path dataRoot;
	private Path lastSelectedDirectory;

	/**
	 * Creates a picker restricted to one data root.
	 *
	 * @param dataRoot the configured PDF data root
	 */
	public PdfFilePicker(Path dataRoot) {
		this(dataRoot, FileChooser::showOpenDialog);
	}

	PdfFilePicker(Path dataRoot, BiFunction<FileChooser, Stage, File> chooserOpener) {
		this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
		this.chooserOpener = Objects.requireNonNull(chooserOpener, "chooserOpener");
	}

	private static boolean isUsableDirectory(Path directory) {
		if (directory == null) {
			return false;
		}
		File file = directory.toFile();
		return file.isDirectory() && file.canRead();
	}

	/**
	 * Shows a PDF chooser without requiring the selected file to be inside the
	 * configured data root.
	 *
	 * @param stage the owner stage
	 * @param title the chooser title
	 * @return the selected absolute PDF path, or {@code null} when cancelled
	 */
	public Path chooseAnyPdf(Stage stage, String title) {
		FileChooser chooser = createFileChooser(title);
		File selectedFile = chooserOpener.apply(chooser, stage);

		// Cancellation must preserve the previous successful directory.
		if (selectedFile == null) {
			return null;
		}
		Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();
		rememberSelectedDirectory(selectedPath);
		return selectedPath;
	}

	/**
	 * Returns the configured root used for managed relative PDF paths.
	 *
	 * @return normalised absolute data root
	 */
	public Path dataRoot() {
		return dataRoot;
	}

	/**
	 * Sets the preferred directory for the next PDF file selection.
	 *
	 * <p>
	 * This supports workflows in which a related non-PDF file, such as a legacy
	 * Excel workbook, has already established a useful folder. After a successful
	 * PDF selection, the normal remembered-directory behaviour resumes.
	 *
	 * <p>
	 * If the preferred directory becomes unavailable, the chooser falls back to its
	 * configured PDF data root.
	 *
	 * @param directory preferred starting directory
	 * @throws NullPointerException if {@code directory} is null
	 */
	public void preferInitialDirectory(Path directory) {
		lastSelectedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
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
		File selectedFile = chooserOpener.apply(chooser, stage);
		if (selectedFile == null) {
			return null;
		}
		Path selectedPath = selectedFile.toPath().toAbsolutePath().normalize();

		// Preserve the existing managed-data-root validation.
		if (!isInsideDataRoot(selectedPath)) {
			showInvalidPathAlert(invalidPathMessage);
			return null;
		}
		rememberSelectedDirectory(selectedPath);
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
		Path initialDirectory = lastSelectedDirectory;

		// A removed or inaccessible directory must never prevent
		// the next file chooser from opening.
		if (!isUsableDirectory(initialDirectory)) {
			initialDirectory = dataRoot;
		}
		if (isUsableDirectory(initialDirectory)) {
			chooser.setInitialDirectory(initialDirectory.toFile());
		}

		// Do not set initialFileName: only the folder is remembered.
		return chooser;
	}

	private void rememberSelectedDirectory(Path selectedPath) {
		lastSelectedDirectory = selectedPath.getParent();
	}

	private void showInvalidPathAlert(String invalidPathMessage) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.setHeaderText(invalidPathMessage);
		alert.setContentText(dataRoot.toString());
		alert.showAndWait();
	}
}
