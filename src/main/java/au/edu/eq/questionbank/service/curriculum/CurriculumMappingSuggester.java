package au.edu.eq.questionbank.service.curriculum;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Produces ranked directional candidate mappings from a non-current curriculum
 * node to the current syllabus version of the same subject.
 */
public interface CurriculumMappingSuggester {

	/**
	 * Finds candidate target nodes for the supplied source node. Implementations do
	 * not confirm or persist the highest-ranked candidate automatically.
	 *
	 * @param source        the node in a non-current syllabus being mapped from
	 * @param targetVersion the current syllabus version to search
	 * @return candidate mappings ordered from strongest to weakest match
	 * @throws NullPointerException     if either argument is {@code null}
	 * @throws IllegalArgumentException if the source is unsupported or the selected
	 *                                  versions violate mapping invariants
	 */
	List<CurriculumMappingSuggestion> suggest(CurriculumNode source, SyllabusVersion targetVersion);
}
