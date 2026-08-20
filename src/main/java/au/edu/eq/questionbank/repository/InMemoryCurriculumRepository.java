package au.edu.eq.questionbank.repository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

public class InMemoryCurriculumRepository implements CurriculumRepository {

	private final List<Subject> subjects;
	private final List<SyllabusVersion> syllabusVersions;
	private final List<CurriculumNode> curriculumNodes;

	public InMemoryCurriculumRepository() {
		this(List.of(), List.of(), List.of());
	}

	public InMemoryCurriculumRepository(List<Subject> subjects, List<SyllabusVersion> syllabusVersions,
			List<CurriculumNode> curriculumNodes) {
		validateUniqueIds(subjects, Subject::getId, "Subject");
		validateUniqueIds(syllabusVersions, SyllabusVersion::getId, "SyllabusVersion");
		validateUniqueIds(curriculumNodes, CurriculumNode::getId, "CurriculumNode");
		this.subjects = List.copyOf(subjects);
		this.syllabusVersions = List.copyOf(syllabusVersions);
		this.curriculumNodes = List.copyOf(curriculumNodes);
	}

	@Override
	public List<Subject> findAllSubjects() {
		return subjects;
	}

	@Override
	public Optional<CurriculumNode> findByCode(SyllabusVersion syllabusVersion, String code) {
		return curriculumNodes.stream().filter(node -> node.getSyllabusVersion().equals(syllabusVersion))
				.filter(node -> node.getCode().equals(code)).findFirst();
	}

	@Override
	public List<CurriculumNode> findChildren(CurriculumNode parent) {
		return curriculumNodes.stream().filter(node -> parent.equals(node.getParent())).sorted(nodeOrder()).toList();
	}

	@Override
	public List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion) {
		return curriculumNodes.stream().filter(node -> node.getSyllabusVersion().equals(syllabusVersion))
				.filter(node -> node.getParent() == null).sorted(nodeOrder()).toList();
	}

	@Override
	public Optional<Subject> findSubjectById(long id) {
		return subjects.stream().filter(subject -> subject.getId() == id).findFirst();
	}

	@Override
	public Optional<SyllabusVersion> findVersionById(long id) {
		return syllabusVersions.stream().filter(version -> version.getId() == id).findFirst();
	}

	@Override
	public List<SyllabusVersion> findVersionsForSubject(Subject subject) {
		return syllabusVersions.stream().filter(version -> version.getSubject().equals(subject)).toList();
	}

	private Comparator<CurriculumNode> nodeOrder() {
		return Comparator.comparingInt(CurriculumNode::getDisplayOrder).thenComparing(CurriculumNode::getCode);
	}

	private <T> void validateUniqueIds(List<T> items, ToLongFunction<T> idFunction, String typeName) {
		Set<Long> ids = new HashSet<>();
		for (T item : items) {
			long id = idFunction.applyAsLong(item);
			if (!ids.add(id)) {
				throw new IllegalArgumentException("Duplicate " + typeName + " id: " + id);
			}
		}
	}
}
