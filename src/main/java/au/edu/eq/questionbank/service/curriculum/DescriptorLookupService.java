package au.edu.eq.questionbank.service.curriculum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;

/**
 * Finds Descriptors within one authoritative syllabus version.
 *
 * Curriculum loading is separate from filtering so a JavaFX caller can load the
 * hierarchy on a background thread and then search an immutable snapshot
 * without repeated database operations.
 */
public final class DescriptorLookupService {

	private final CurriculumRepository repository;

	/**
	 * Creates a Descriptor lookup service.
	 *
	 * @param repository authoritative curriculum repository
	 */
	public DescriptorLookupService(CurriculumRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository");
	}

	/**
	 * Finds matching Descriptors within the requested syllabus.
	 *
	 * Search terms are case-insensitive, whitespace-separated and combined using
	 * AND. Each term may match any substring of the Descriptor code or wording.
	 *
	 * @param syllabusVersion authoritative selected syllabus
	 * @param descriptors     previously loaded Descriptor snapshot
	 * @param query           search terms, possibly blank
	 * @param maximumResults  maximum number of returned matches
	 * @return immutable, bounded matches in source order
	 */
	public List<DescriptorMatch> findMatches(SyllabusVersion syllabusVersion, List<CurriculumNode> descriptors,
			String query, int maximumResults) {
		return findMatches(syllabusVersion, descriptors, query, "", maximumResults);
	}

	/**
	 * Finds matching Descriptors within one syllabus and code prefix.
	 *
	 * Search terms are case-insensitive AND terms. A non-blank code prefix
	 * restricts matches to that code or its descendants at a dot-delimited
	 * hierarchy boundary.
	 *
	 * @param syllabusVersion selected syllabus version
	 * @param descriptors     previously loaded Descriptor snapshot
	 * @param query           Descriptor code/wording keywords
	 * @param codePrefix      optional Unit, Topic or partial classification code
	 * @param maximumResults  positive maximum number of matches
	 * @return immutable matching results in source order
	 */
	public List<DescriptorMatch> findMatches(SyllabusVersion syllabusVersion, List<CurriculumNode> descriptors,
			String query, String codePrefix, int maximumResults) {
		Objects.requireNonNull(syllabusVersion, "syllabusVersion");
		Objects.requireNonNull(descriptors, "descriptors");
		if (maximumResults < 1) {
			throw new IllegalArgumentException("maximumResults must be positive");
		}
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String[] terms = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
		String prefix = codePrefix == null ? "" : codePrefix.strip();
		String descendantPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
		List<DescriptorMatch> matches = new ArrayList<>();
		for (CurriculumNode descriptor : descriptors) {
			if (descriptor.getLevel() != CurriculumLevel.DESCRIPTOR
					|| !syllabusVersion.equals(descriptor.getSyllabusVersion())) {
				continue;
			}
			String code = descriptor.getCode();

			// A Topic code 3.1 may match 3.1.1 and 3.1.2,
			// but must not match 3.10.1.
			if (!prefix.isEmpty() && !code.equals(prefix) && !code.startsWith(descendantPrefix)) {
				continue;
			}
			String searchableText = (code + " " + descriptor.getName()).toLowerCase(Locale.ROOT);
			boolean matchesAllTerms = true;
			for (String term : terms) {
				if (!searchableText.contains(term)) {
					matchesAllTerms = false;
					break;
				}
			}
			if (!matchesAllTerms) {
				continue;
			}
			matches.add(new DescriptorMatch(descriptor, formatHierarchy(descriptor)));
			if (matches.size() == maximumResults) {
				break;
			}
		}
		return List.copyOf(matches);
	}

	/**
	 * Loads all Descriptors belonging to one syllabus version.
	 *
	 * Call this method off the JavaFX application thread when the repository uses
	 * persistent storage.
	 *
	 * @param syllabusVersion selected syllabus version
	 * @return immutable Descriptor snapshot in repository order
	 */
	public List<CurriculumNode> loadDescriptors(SyllabusVersion syllabusVersion) {
		Objects.requireNonNull(syllabusVersion, "syllabusVersion");
		List<CurriculumNode> descriptors = new ArrayList<>();

		// The repository already provides a bulk hierarchy read.
		// No per-Descriptor SQL query is necessary.
		for (CurriculumNode node : repository.findReviewNodes(syllabusVersion)) {
			if (node.getLevel() == CurriculumLevel.DESCRIPTOR && syllabusVersion.equals(node.getSyllabusVersion())) {
				descriptors.add(node);
			}
		}
		return List.copyOf(descriptors);
	}

	private String formatHierarchy(CurriculumNode descriptor) {
		List<String> path = new ArrayList<>();
		CurriculumNode node = descriptor;

		// A Descriptor can be directly beneath a Topic, or beneath
		// a Subtopic. Follow actual parent relationships rather
		// than assuming every syllabus has four levels.
		while (node != null) {
			path.add(node.getCode() + " — " + node.getName());
			node = node.getParent();
		}
		Collections.reverse(path);
		return String.join(" → ", path);
	}

	/**
	 * One matching Descriptor and its complete readable path.
	 *
	 * @param descriptor    authoritative curriculum node
	 * @param hierarchyPath complete Unit-to-Descriptor path
	 */
	public record DescriptorMatch(CurriculumNode descriptor, String hierarchyPath) {

		/**
		 * Validates the Descriptor match components.
		 *
		 * @throws NullPointerException     if either component is null
		 * @throws IllegalArgumentException if the node is not a Descriptor
		 */
		public DescriptorMatch {
			Objects.requireNonNull(descriptor, "descriptor");
			Objects.requireNonNull(hierarchyPath, "hierarchyPath");
			if (descriptor.getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalArgumentException("Match must contain a Descriptor");
			}
		}
	}
}
