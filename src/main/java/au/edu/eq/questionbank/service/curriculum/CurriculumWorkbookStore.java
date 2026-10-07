package au.edu.eq.questionbank.service.curriculum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import au.edu.eq.questionbank.ManagedDataLayout;

/**
 * Manages retained curriculum workbooks.
 * <p>
 * New workbooks use the canonical Subject-first layout beneath
 * {@code subjects/<Subject>/curriculum/<Version>/workbooks}. The legacy
 * curriculum-root constructor remains temporarily available for compatibility
 * until existing installations are migrated.
 */
public final class CurriculumWorkbookStore {

	private final Path curriculumDataRoot;
	private final ManagedDataLayout managedDataLayout;

	/**
	 * Creates a workbook store using the Subject-first managed-data layout.
	 *
	 * @param managedDataLayout canonical application managed-data layout
	 * @throws NullPointerException if {@code managedDataLayout} is {@code null}
	 */
	public CurriculumWorkbookStore(ManagedDataLayout managedDataLayout) {
		if (managedDataLayout == null) {
			throw new NullPointerException("managedDataLayout");
		}
		curriculumDataRoot = null;
		this.managedDataLayout = managedDataLayout;
	}

	/**
	 * Creates a workbook store using the legacy curriculum-root layout.
	 *
	 * @param curriculumDataRoot legacy managed curriculum directory
	 * @throws NullPointerException if {@code curriculumDataRoot} is {@code null}
	 */
	public CurriculumWorkbookStore(Path curriculumDataRoot) {
		if (curriculumDataRoot == null) {
			throw new NullPointerException("curriculumDataRoot");
		}
		this.curriculumDataRoot = curriculumDataRoot.toAbsolutePath().normalize();
		managedDataLayout = null;
	}

	/**
	 * Copies a selected curriculum workbook into the legacy managed curriculum
	 * directory.
	 * <p>
	 * Existing managed files are reused when they contain identical bytes.
	 *
	 * @param sourceWorkbook workbook selected by the user
	 * @return absolute managed workbook path
	 * @throws IOException              if the source cannot be read or copied
	 * @throws NullPointerException     if {@code sourceWorkbook} is {@code null}
	 * @throws IllegalArgumentException if the source is not an Excel workbook
	 * @throws IllegalStateException    if this store uses the Subject-first layout
	 */
	public Path manageWorkbook(Path sourceWorkbook) throws IOException {
		if (managedDataLayout != null) {
			throw new IllegalStateException("Subject-first curriculum storage requires Subject and syllabus version");
		}
		return manageWorkbook(sourceWorkbook, curriculumDataRoot);
	}

	/**
	 * Copies a selected curriculum workbook into the owning Subject and syllabus
	 * version directory.
	 *
	 * @param subjectName     owning Subject name
	 * @param syllabusVersion owning syllabus-version name
	 * @param sourceWorkbook  workbook selected by the user
	 * @return absolute managed workbook path
	 * @throws IOException              if the source cannot be read or copied
	 * @throws NullPointerException     if {@code sourceWorkbook} is {@code null}
	 * @throws IllegalArgumentException if the source or managed directory identity
	 *                                  is invalid
	 * @throws IllegalStateException    if this store uses the legacy layout
	 */
	public Path manageWorkbook(String subjectName, String syllabusVersion, Path sourceWorkbook) throws IOException {
		ManagedDataLayout layout = requireSubjectFirstLayout();
		Path destinationDirectory = layout.curriculumWorkbookDirectory(subjectName, syllabusVersion);
		return manageWorkbook(sourceWorkbook, destinationDirectory);
	}

	private Path manageWorkbook(Path sourceWorkbook, Path destinationDirectory) throws IOException {
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
		Path managedDirectory = destinationDirectory.toAbsolutePath().normalize();
		Files.createDirectories(managedDirectory);

		// A workbook already inside its authoritative Subject/version directory is
		// already managed and requires no copy.
		if (source.startsWith(managedDirectory)) {
			return source;
		}
		Path destination = managedDirectory.resolve(fileName).normalize();
		if (!destination.startsWith(managedDirectory)) {
			throw new IllegalArgumentException("Curriculum workbook destination escapes the managed directory");
		}
		if (!Files.exists(destination)) {
			return Files.copy(source, destination);
		}

		// Byte-identical selection reuses the existing authoritative workbook.
		if (Files.isSameFile(source, destination) || Files.mismatch(source, destination) == -1) {
			return destination;
		}

		// Preserve the existing collision policy: never overwrite different bytes
		// merely because the external files share a filename.
		Path uniqueDestination;
		do {
			uniqueDestination = managedDirectory.resolve(UUID.randomUUID() + "--" + fileName).normalize();
		} while (Files.exists(uniqueDestination));
		return Files.copy(source, uniqueDestination);
	}

	private ManagedDataLayout requireSubjectFirstLayout() {
		if (managedDataLayout == null) {
			throw new IllegalStateException("CurriculumWorkbookStore was created with the legacy curriculum root");
		}
		return managedDataLayout;
	}
}
