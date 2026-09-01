package au.edu.eq.questionbank.repository.curriculum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * Immutable in-memory snapshot of directional curriculum mappings.
 */
public class InMemoryCurriculumMappingRepository implements CurriculumMappingRepository {

	private final List<CurriculumMapping> mappings;

	/**
	 * Creates a repository from a snapshot of the supplied mappings.
	 *
	 * @param mappings mappings with unique persistent identifiers
	 * @throws NullPointerException     if the list or one of its elements is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if mapping identifiers are duplicated
	 */
	public InMemoryCurriculumMappingRepository(List<CurriculumMapping> mappings) {
		if (mappings == null) {
			throw new NullPointerException("mappings");
		}
		Set<Long> ids = new HashSet<>();
		for (CurriculumMapping mapping : mappings) {
			if (!ids.add(mapping.getId())) {
				throw new IllegalArgumentException("Duplicate CurriculumMapping id: " + mapping.getId());
			}
		}
		this.mappings = List.copyOf(mappings);
	}

	@Override
	public List<CurriculumMapping> findAll() {
		return mappings;
	}

	@Override
	public List<CurriculumMapping> findSources(CurriculumNode target) {
		List<CurriculumMapping> sources = new ArrayList<>();
		for (CurriculumMapping mapping : mappings) {
			if (mapping.getTarget().equals(target)) {
				sources.add(mapping);
			}
		}
		return List.copyOf(sources);
	}

	@Override
	public List<CurriculumMapping> findTargets(CurriculumNode source) {
		List<CurriculumMapping> targets = new ArrayList<>();
		for (CurriculumMapping mapping : mappings) {
			if (mapping.getSource().equals(source)) {
				targets.add(mapping);
			}
		}
		return List.copyOf(targets);
	}
}
