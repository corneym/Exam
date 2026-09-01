package au.edu.eq.questionbank.repository.curriculum;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * Read-only lookup boundary for directional mappings between syllabus
 * versions.
 */
public interface CurriculumMappingRepository {

	/**
	 * @return all curriculum mappings
	 */
	List<CurriculumMapping> findAll();

	/**
	 * Finds mappings whose target is the supplied node.
	 *
	 * @param target the target-side curriculum node
	 * @return mappings leading to {@code target}
	 */
	List<CurriculumMapping> findSources(CurriculumNode target);

	/**
	 * Finds mappings whose source is the supplied node.
	 *
	 * @param source the source-side curriculum node
	 * @return mappings leading from {@code source}
	 */
	List<CurriculumMapping> findTargets(CurriculumNode source);
}
