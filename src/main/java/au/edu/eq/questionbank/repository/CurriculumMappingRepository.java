package au.edu.eq.questionbank.repository;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumMapping;
import au.edu.eq.questionbank.model.CurriculumNode;

public interface CurriculumMappingRepository {

	List<CurriculumMapping> findAll();

	List<CurriculumMapping> findSources(CurriculumNode target);

	List<CurriculumMapping> findTargets(CurriculumNode source);
}