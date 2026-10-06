package au.edu.eq.questionbank;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Defines the canonical filesystem layout for managed application data.
 * <p>
 * Managed Subject assets are stored beneath {@code subjects/<Subject>}. This
 * class calculates paths only; it does not create, move, or delete files or
 * directories.
 */
public final class ManagedDataLayout {

	private static final String CURRICULUM_DIRECTORY = "curriculum";
	private static final String EXAMS_DIRECTORY = "exams";
	private static final String LEGACY_DIRECTORY = "legacy";
	private static final String SOURCES_DIRECTORY = "sources";
	private static final String SUBJECTS_DIRECTORY = "subjects";
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3",
			"COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7",
			"LPT8", "LPT9");
	private static final String WORKBOOKS_DIRECTORY = "workbooks";

	private final Path dataRoot;

	/**
	 * Creates a managed-data layout rooted at the supplied application data root.
	 *
	 * @param dataRoot application data root
	 * @throws NullPointerException if {@code dataRoot} is {@code null}
	 */
	public ManagedDataLayout(Path dataRoot) {
		if (dataRoot == null) {
			throw new NullPointerException("dataRoot");
		}
		this.dataRoot = dataRoot.toAbsolutePath().normalize();
	}

	private static boolean isWindowsAbsolutePath(String path) {
		return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
	}

	private static String validateComponent(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		String trimmed = value.trim();
		if (".".equals(trimmed) || "..".equals(trimmed)) {
			throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
		}

		for (int index = 0; index < trimmed.length(); index++) {
			char character = trimmed.charAt(index);
			if (character < 32 || "<>:\"/\\|?*".indexOf(character) >= 0) {
				throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
			}
		}
		if (trimmed.endsWith(".")) {
			throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
		}

		// Windows device names remain reserved even when an extension is present.
		String stem = trimmed;
		int extensionSeparator = stem.indexOf('.');
		if (extensionSeparator >= 0) {
			stem = stem.substring(0, extensionSeparator);
		}
		if (WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
			throw new IllegalArgumentException(fieldName + " is not a valid directory name: " + value);
		}
		return trimmed;
	}

	/**
	 * Returns the curriculum directory for one Subject.
	 *
	 * @param subjectName Subject name
	 * @return absolute curriculum directory
	 */
	public Path curriculumDirectory(String subjectName) {
		return subjectDirectory(subjectName).resolve(CURRICULUM_DIRECTORY);
	}

	/**
	 * Returns the managed source-document directory for one syllabus version.
	 *
	 * @param subjectName     Subject name
	 * @param syllabusVersion syllabus version name
	 * @return absolute curriculum source directory
	 */
	public Path curriculumSourceDirectory(String subjectName, String syllabusVersion) {
		return curriculumVersionDirectory(subjectName, syllabusVersion).resolve(SOURCES_DIRECTORY);
	}

	/**
	 * Returns the curriculum directory for one syllabus version.
	 *
	 * @param subjectName     Subject name
	 * @param syllabusVersion syllabus version name
	 * @return absolute syllabus-version directory
	 */
	public Path curriculumVersionDirectory(String subjectName, String syllabusVersion) {
		String versionDirectory = validateComponent(syllabusVersion, "syllabusVersion");
		return curriculumDirectory(subjectName).resolve(versionDirectory);
	}

	/**
	 * Returns the managed workbook directory for one syllabus version.
	 *
	 * @param subjectName     Subject name
	 * @param syllabusVersion syllabus version name
	 * @return absolute curriculum workbook directory
	 */
	public Path curriculumWorkbookDirectory(String subjectName, String syllabusVersion) {
		return curriculumVersionDirectory(subjectName, syllabusVersion).resolve(WORKBOOKS_DIRECTORY);
	}

	/**
	 * Returns the application data root.
	 *
	 * @return absolute normalized application data root
	 */
	public Path dataRoot() {
		return dataRoot;
	}

	/**
	 * Returns the canonical directory for one Exam.
	 *
	 * @param subjectName    Subject name
	 * @param providerName   examination provider name
	 * @param year           examination year
	 * @param assessmentName assessment name
	 * @return absolute Exam directory
	 * @throws IllegalArgumentException if a directory component is invalid or
	 *                                  {@code year} is not positive
	 */
	public Path examDirectory(String subjectName, String providerName, int year, String assessmentName) {
		String assessmentDirectory = validateComponent(assessmentName, "assessmentName");
		return examYearDirectory(subjectName, providerName, year).resolve(assessmentDirectory);
	}

	/**
	 * Returns the examination-provider directory for one Subject.
	 *
	 * @param subjectName  Subject name
	 * @param providerName examination provider name
	 * @return absolute examination-provider directory
	 */
	public Path examProviderDirectory(String subjectName, String providerName) {
		String providerDirectory = validateComponent(providerName, "providerName");
		return examsDirectory(subjectName).resolve(providerDirectory);
	}

	/**
	 * Returns the root Exam directory for one Subject.
	 *
	 * @param subjectName Subject name
	 * @return absolute Subject Exam directory
	 */
	public Path examsDirectory(String subjectName) {
		return subjectDirectory(subjectName).resolve(EXAMS_DIRECTORY);
	}

	/**
	 * Returns the examination-year directory for one provider and Subject.
	 *
	 * @param subjectName  Subject name
	 * @param providerName examination provider name
	 * @param year         examination year
	 * @return absolute examination-year directory
	 * @throws IllegalArgumentException if {@code year} is not positive
	 */
	public Path examYearDirectory(String subjectName, String providerName, int year) {
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		return examProviderDirectory(subjectName, providerName).resolve(Integer.toString(year));
	}

	/**
	 * Returns the managed legacy-import directory for one syllabus version.
	 *
	 * @param subjectName     Subject name
	 * @param syllabusVersion syllabus version name
	 * @return absolute legacy-import directory
	 */
	public Path legacyImportDirectory(String subjectName, String syllabusVersion) {
		String versionDirectory = validateComponent(syllabusVersion, "syllabusVersion");
		return subjectDirectory(subjectName).resolve(LEGACY_DIRECTORY).resolve(versionDirectory);
	}

	/**
	 * Converts an absolute managed path to the portable data-root-relative form
	 * used for persistence.
	 *
	 * @param managedPath absolute path beneath the application data root
	 * @return data-root-relative path using forward slashes
	 * @throws NullPointerException     if {@code managedPath} is {@code null}
	 * @throws IllegalArgumentException if the path is relative, identifies the data
	 *                                  root itself, or lies outside the data root
	 */
	public String relativePath(Path managedPath) {
		if (managedPath == null) {
			throw new NullPointerException("managedPath");
		}
		if (!managedPath.isAbsolute()) {
			throw new IllegalArgumentException("Managed path must be absolute: " + managedPath);
		}
		Path normalisedPath = managedPath.normalize();
		if (normalisedPath.equals(dataRoot) || !normalisedPath.startsWith(dataRoot)) {
			throw new IllegalArgumentException(
					"Managed path must be beneath the application data root: " + managedPath);
		}

		// Persist separators independently of the operating system so database paths
		// remain portable between Windows and CI environments.
		return dataRoot.relativize(normalisedPath).toString().replace('\\', '/');
	}

	/**
	 * Resolves a persisted data-root-relative managed path.
	 *
	 * @param relativePath persisted path relative to the application data root
	 * @return absolute normalized managed path
	 * @throws NullPointerException     if {@code relativePath} is {@code null}
	 * @throws IllegalArgumentException if the path is blank, absolute, identifies
	 *                                  the data root itself, or escapes the data
	 *                                  root
	 */
	public Path resolve(String relativePath) {
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (relativePath.isBlank()) {
			throw new IllegalArgumentException("Managed path must not be blank");
		}

		// Interpret both separator forms consistently so traversal and Windows paths
		// are rejected the same way under Windows and Linux CI.
		String portablePath = relativePath.replace('\\', '/');
		if (portablePath.startsWith("/") || isWindowsAbsolutePath(portablePath)) {
			throw new IllegalArgumentException("Managed path must be relative: " + relativePath);
		}
		Path path = Path.of(portablePath);
		if (path.isAbsolute() || path.getRoot() != null) {
			throw new IllegalArgumentException("Managed path must be relative: " + relativePath);
		}
		Path resolved = dataRoot.resolve(path).normalize();
		if (resolved.equals(dataRoot) || !resolved.startsWith(dataRoot)) {
			throw new IllegalArgumentException(
					"Managed path must remain beneath the application data root: " + relativePath);
		}
		return resolved;
	}

	/**
	 * Returns the managed root directory for one Subject.
	 *
	 * @param subjectName Subject name
	 * @return absolute Subject directory
	 */
	public Path subjectDirectory(String subjectName) {
		String subjectDirectory = validateComponent(subjectName, "subjectName");
		return subjectsRoot().resolve(subjectDirectory);
	}

	/**
	 * Returns the root beneath which all Subject-managed data is stored.
	 *
	 * @return absolute Subjects root
	 */
	public Path subjectsRoot() {
		return dataRoot.resolve(SUBJECTS_DIRECTORY);
	}
}
