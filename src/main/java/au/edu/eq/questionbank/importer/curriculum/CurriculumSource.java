package au.edu.eq.questionbank.importer.curriculum;

import java.nio.file.Path;
import java.util.List;

import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * The workbook files that collectively define one syllabus version's
 * curriculum hierarchy.
 *
 * @param syllabusVersion the version described by the workbooks
 * @param workbooks       one or more workbook paths, in import order
 */
public record CurriculumSource(SyllabusVersion syllabusVersion, List<Path> workbooks) {

	/**
	 * Creates and validates this value.
	 *
	 * @throws NullPointerException     if {@code syllabusVersion} is {@code null}
	 *                                  or a workbook element is {@code null}
	 * @throws IllegalArgumentException if {@code workbooks} is {@code null} or empty
	 */
	public CurriculumSource {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}
		if (workbooks == null || workbooks.isEmpty()) {
			throw new IllegalArgumentException("workbooks must not be empty");
		}

		// Freeze both membership and import order against later changes to the caller's list.
		workbooks = List.copyOf(workbooks);
	}
}
