package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;

/**
 * A PDF selected beneath the configured data root, retaining both file-system
 * and JavaFX file-chooser representations.
 */
record SelectedPdf(File file, Path path, Path dataRoot) {

	/**
	 * Returns the source-document path relative to the configured data root.
	 *
	 * @return the data-root-relative path
	 */
	String relativePath() {
		return dataRoot.relativize(path).toString();
	}
}
