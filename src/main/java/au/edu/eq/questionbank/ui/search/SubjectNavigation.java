package au.edu.eq.questionbank.ui.search;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Loaded current-syllabus navigation state for one selected Subject.
 *
 * @param currentSyllabus current syllabus version, or {@code null} when none
 *                        exists
 * @param units           Units belonging to the current syllabus
 */
record SubjectNavigation(SyllabusVersion currentSyllabus, List<CurriculumNode> units) {

	SubjectNavigation {

		// Navigation results are background-loaded and then published to the UI, so
		// retain an immutable snapshot of the loaded Unit list.
		units = List.copyOf(units);
	}
}
