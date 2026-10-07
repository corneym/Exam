package au.edu.eq.questionbank.service.curriculum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Copies curriculum Excel workbooks into the configured managed curriculum
 * directory.
 */
public final class CurriculumWorkbookStore {

	private final Path curriculumDataRoot;

	/**
	 * Creates a workbook store beneath the configured curriculum data root.
	 *
	 * @param curriculumDataRoot managed curriculum directory
	 * @throws NullPointerException if {@code curriculumDataRoot} is {@code null}
	 */
	public CurriculumWorkbookStore(Path curriculumDataRoot) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
	}

	/**
	 * Copies a selected curriculum workbook into managed curriculum storage.
	 * Existing managed files are reused when they contain identical bytes.
	 *
	 * @param sourceWorkbook workbook selected by the user
	 * @return absolute managed workbook path
	 * @throws IOException              if the source cannot be read or copied
	 * @throws NullPointerException     if {@code sourceWorkbook} is {@code null}
	 * @throws IllegalArgumentException if the source is not an Excel workbook
	 */
	public Path manageWorkbook(Path sourceWorkbook) throws IOException {
		if (sourceWorkbook == null) {
			throw new NullPointerException("sourceWorkbook");
		}
		Path source = sourceWorkbook.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Curriculum workbook is not a regular file: " + source);
		}
		Path fileName = source.getFileName();
		if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
			throw new IllegalArgumentException("Curriculum workbook must be an .xlsx file: " + source);
		}
		Files.createDirectories(curriculumDataRoot);

		// Workbooks already beneath the managed curriculum root retain their current
		// location for compatibility with existing installations.
		if (source.startsWith(curriculumDataRoot)) {
			return source;
		}
		Path destination = curriculumDataRoot.resolve(fileName).normalize();
		if (!destination.startsWith(curriculumDataRoot)) {
			throw new IllegalArgumentException("Curriculum workbook destination escapes the managed directory");
		}
		if (!Files.exists(destination)) {
			return Files.copy(source, destination);
		}

		// Reuse the existing managed workbook when it contains exactly the selected
		// bytes.
		if (Files.isSameFile(source, destination) || Files.mismatch(source, destination) == -1) {
			return destination;
		}

		// Never overwrite a different workbook merely because it has the same source
		// filename.
		Path uniqueDestination;
		do {
			uniqueDestination = curriculumDataRoot.resolve(UUID.randomUUID() + "--" + fileName).normalize();
		} while (Files.exists(uniqueDestination));
		return Files.copy(source, uniqueDestination);
	}
}
