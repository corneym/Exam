package au.edu.eq.questionbank.repository;

import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

public interface CurriculumRepository {
	List<Subject> findAllSubjects();

	Optional<Subject> findSubjectById(long id);

	List<SyllabusVersion> findVersionsForSubject(Subject subject);

	Optional<SyllabusVersion> findVersionById(long id);

	List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion);

	List<CurriculumNode> findChildren(CurriculumNode parent);

	Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code);
}
