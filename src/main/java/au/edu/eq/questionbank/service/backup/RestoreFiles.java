package au.edu.eq.questionbank.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class RestoreFiles {

	private RestoreFiles() {
	}

	static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(root)) {

			// Order children before parents and close the directory walk before deletion
			// begins.
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}
}
