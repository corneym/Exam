package au.edu.eq.questionbank.ui;

import java.io.File;
import java.nio.file.Path;

record SelectedPdf(File file, Path path, Path dataRoot) {

	String relativePath() {
		return dataRoot.relativize(path).toString();
	}
}
