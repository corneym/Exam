package au.edu.eq.questionbank.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Immutable in-memory snapshot of subjects, syllabus versions, and curriculum
 * nodes.
 * <p>
 * Hierarchy queries order nodes by display order and then classification code.
 */
public class InMemoryCurriculumRepository implements CurriculumRepository {

	private final List<Subject> subjects;
	private final List<SyllabusVersion> syllabusVersions;
	private final List<CurriculumNode> curriculumNodes;

	/**
	 * Creates an empty curriculum repository.
	 */
	public InMemoryCurriculumRepository() {
		this(List.of(), List.of(), List.of());
	}

	/**
	 * Creates a repository from immutable snapshots of the supplied collections.
	 * Identifiers must be unique within each model type.
	 *
	 * @param subjects         the available subjects
	 * @param syllabusVersions the available versions
	 * @param curriculumNodes  all nodes in the version hierarchies
	 * @throws NullPointerException     if a collection or one of its elements is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if a model type contains duplicate
	 *                                  identifiers
	 */
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
		for (CurriculumNode node : curriculumNodes) {
			boolean sameSyllabusVersion = node.getSyllabusVersion().equals(syllabusVersion);
			if (sameSyllabusVersion && node.getCode().equals(code)) {
				return Optional.of(node);
			}
		}
		return Optional.empty();
	}

	@Override
	public List<CurriculumNode> findChildren(CurriculumNode parent) {
		List<CurriculumNode> children = new ArrayList<>();
		for (CurriculumNode node : curriculumNodes) {
			if (parent.equals(node.getParent())) {
				children.add(node);
			}
		}
		children.sort(nodeOrder());
		return List.copyOf(children);
	}

	@Override
	public List<CurriculumNode> findRootNodes(SyllabusVersion syllabusVersion) {
		List<CurriculumNode> rootNodes = new ArrayList<>();
		for (CurriculumNode node : curriculumNodes) {
			boolean sameSyllabusVersion = node.getSyllabusVersion().equals(syllabusVersion);
			if (sameSyllabusVersion && node.getParent() == null) {
				rootNodes.add(node);
			}
		}
		rootNodes.sort(nodeOrder());
		return List.copyOf(rootNodes);
	}

	@Override
	public Optional<Subject> findSubjectById(long id) {
		for (Subject subject : subjects) {
			if (subject.getId() == id) {
				return Optional.of(subject);
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<SyllabusVersion> findVersionById(long id) {
		for (SyllabusVersion version : syllabusVersions) {
			if (version.getId() == id) {
				return Optional.of(version);
			}
		}
		return Optional.empty();
	}

	@Override
	public List<SyllabusVersion> findVersionsForSubject(Subject subject) {
		List<SyllabusVersion> versions = new ArrayList<>();
		for (SyllabusVersion version : syllabusVersions) {
			if (version.getSubject().equals(subject)) {
				versions.add(version);
			}
		}
		return List.copyOf(versions);
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
