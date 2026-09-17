package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * Persistence-facing representation of one curriculum node used when opening an
 * existing syllabus for editing.
 *
 * @param persistentId       SQLite curriculum-node identifier
 * @param level              explicit curriculum level
 * @param code               curriculum code
 * @param name               curriculum text
 * @param parentPersistentId persistent parent identifier, or {@code null}
 * @param displayOrder       sibling display order
 * @param sourcePageNumber   one-based syllabus source page, or {@code null}
 */
public record PersistedCurriculumNode(long persistentId, CurriculumLevel level, String code, String name,
		Long parentPersistentId, int displayOrder, Integer sourcePageNumber) {

	/**
	 * Validates a stored curriculum-node snapshot, including identity and
	 * source-page provenance.
	 *
	 * @param persistentId       SQLite curriculum-node identifier
	 * @param level              explicit curriculum level
	 * @param code               curriculum code
	 * @param name               curriculum text
	 * @param parentPersistentId persistent parent identifier, or {@code null}
	 * @param displayOrder       sibling display order
	 * @param sourcePageNumber   one-based syllabus source page, or {@code null}
	 */
	public PersistedCurriculumNode {
		if (persistentId < 1) {
			throw new IllegalArgumentException("persistentId must be positive");
		}
		if (level == null) {
			throw new NullPointerException("level");
		}
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (parentPersistentId != null && parentPersistentId < 1) {
			throw new IllegalArgumentException("parentPersistentId must be positive");
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative");
		}
		if (sourcePageNumber != null && sourcePageNumber < 1) {
			throw new IllegalArgumentException("sourcePageNumber must be positive");
		}
	}
}
