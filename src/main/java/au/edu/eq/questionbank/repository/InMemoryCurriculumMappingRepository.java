package au.edu.eq.questionbank.repository;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;

public class InMemoryCurriculumMappingRepository implements CurriculumMappingRepository {

	private final List<CurriculumMapping> mappings;

	public InMemoryCurriculumMappingRepository(List<CurriculumMapping> mappings) {

		if (mappings == null) {
			throw new NullPointerException("mappings");
		}

		this.mappings = List.copyOf(mappings);
	}

	@Override
	public List<CurriculumMapping> findAll() {
		return mappings;
	}

	@Override
	public List<CurriculumMapping> findSources(CurriculumNode target) {

		return mappings.stream().filter(mapping -> mapping.getTarget().equals(target)).toList();
	}

	@Override
	public List<CurriculumMapping> findTargets(CurriculumNode source) {

		return mappings.stream().filter(mapping -> mapping.getSource().equals(source)).toList();
	}
}