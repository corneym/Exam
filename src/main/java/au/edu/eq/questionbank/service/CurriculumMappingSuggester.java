package au.edu.eq.questionbank.service;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Produces ranked candidate mappings for a curriculum node in another
 * explicitly selected syllabus version.
 */
public interface CurriculumMappingSuggester {
	/**
	 * Finds candidate target nodes for the supplied source node.
	 *
	 * @param source        the node being mapped from
	 * @param targetVersion the syllabus version to search
	 * @return candidate mappings ordered from strongest to weakest match
	 */
	List<CurriculumMappingSuggestion> suggest(CurriculumNode source, SyllabusVersion targetVersion);
}
