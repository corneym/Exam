package au.edu.eq.questionbank.service.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;

/**
 * Retains legacy Question-import workbooks as managed Subject assets.
 * <p>
 * Workbooks are stored beneath
 * {@code subjects/<Subject>/legacy/<SyllabusVersion>}. The workbook remains
 * provenance material only; imported Question metadata in SQLite remains
 * authoritative.
 */
public final class LegacyQuestionWorkbookStore {

	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates a legacy-workbook store using the canonical application data layout.
	 *
	 * @param managedDataLayout canonical application managed-data layout
	 * @throws NullPointerException if {@code managedDataLayout} is {@code null}
	 */
	public LegacyQuestionWorkbookStore(ManagedDataLayout managedDataLayout) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		this.managedDataLayout = managedDataLayout;
	}

	/**
	 * Copies a selected legacy workbook into the owning Subject and
	 * syllabus-version directory.
	 * <p>
	 * An existing byte-identical workbook with the same filename is reused. A
	 * different workbook with the same filename receives a UUID-qualified filename
	 * rather than overwriting the existing managed asset.
	 *
	 * @param subjectName     owning Subject name
	 * @param syllabusVersion syllabus version used by the legacy workbook
	 * @param sourceWorkbook  selected external or already-managed workbook
	 * @return absolute managed workbook path
	 * @throws IOException              if the source cannot be read or copied
	 * @throws NullPointerException     if {@code sourceWorkbook} is {@code null}
	 * @throws IllegalArgumentException if the source is not an {@code .xlsx}
	 *                                  workbook or the managed identity is invalid
	 */
	public Path manageWorkbook(String subjectName, String syllabusVersion, Path sourceWorkbook) throws IOException {
		if (sourceWorkbook == null) {
			throw new NullPointerException("sourceWorkbook");
		}
		Path source = sourceWorkbook.toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Legacy Question workbook is not a regular file: " + source);
		}
		Path fileName = source.getFileName();
		if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
			throw new IllegalArgumentException("Legacy Question workbook must be an .xlsx file: " + source);
		}
		Path managedDirectory = managedDataLayout.legacyImportDirectory(subjectName, syllabusVersion).toAbsolutePath()
				.normalize();
		Files.createDirectories(managedDirectory);

		// A workbook already retained inside this Subject/version package is already
		// authoritative provenance for this intake.
		if (source.startsWith(managedDirectory)) {
			return source;
		}
		Path destination = managedDirectory.resolve(fileName).normalize();
		if (!destination.startsWith(managedDirectory)) {
			throw new IllegalArgumentException("Legacy workbook destination escapes the managed directory");
		}
		if (!Files.exists(destination)) {
			return Files.copy(source, destination);
		}
		if (Files.isSameFile(source, destination) || Files.mismatch(source, destination) == -1) {
			return destination;
		}

		// Preserve both workbooks when unrelated source files happen to share a name.
		Path uniqueDestination;
		do {
			uniqueDestination = managedDirectory.resolve(UUID.randomUUID() + "--" + fileName).normalize();
		} while (Files.exists(uniqueDestination));
		return Files.copy(source, uniqueDestination);
	}
}
