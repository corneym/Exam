package au.edu.eq.questionbank.service.curriculum;

import java.util.Objects;

import au.edu.eq.questionbank.model.CurriculumLevel;

/**
 * One transient curriculum node being prepared for authoring and later
 * persistence.
 * <p>
 * Draft identity and parent relationships are independent of curriculum codes
 * so codes can be edited without breaking the draft hierarchy.
 * <p>
 * This value validates only its own scalar values. Relationships between nodes,
 * including legal parent types and orphan detection, are validated across the
 * complete draft separately.
 *
 * @param draftId          stable positive identifier within the draft
 * @param level            explicitly selected curriculum level
 * @param code             non-blank curriculum code
 * @param name             non-blank curriculum label or descriptor text
 * @param parentDraftId    parent draft identifier, or {@code null} where no
 *                         parent has yet been assigned
 * @param displayOrder     zero-based or positive sibling display order
 * @param sourcePageNumber one-based syllabus-PDF page number, or {@code null}
 *                         when no source page is recorded
 */
public record CurriculumDraftNode(long draftId, CurriculumLevel level, String code, String name, Long parentDraftId,
		int displayOrder, Integer sourcePageNumber) {

	/**
	 * Creates and validates this draft node.
	 *
	 * @throws NullPointerException     if {@code level} is {@code null}
	 * @throws IllegalArgumentException if an identifier is invalid, code or name is
	 *                                  blank, display order is negative, or a
	 *                                  source page number is less than one
	 */
	public CurriculumDraftNode {
		if (draftId < 1) {
			throw new IllegalArgumentException("draftId must be at least 1: " + draftId);
		}
		Objects.requireNonNull(level, "level");
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (parentDraftId != null) {
			if (parentDraftId < 1) {
				throw new IllegalArgumentException("parentDraftId must be at least 1: " + parentDraftId);
			}
			if (parentDraftId == draftId) {
				throw new IllegalArgumentException("a draft node cannot be its own parent");
			}
		}
		if (displayOrder < 0) {
			throw new IllegalArgumentException("displayOrder must not be negative: " + displayOrder);
		}
		if (sourcePageNumber != null && sourcePageNumber < 1) {
			throw new IllegalArgumentException("sourcePageNumber must be at least 1: " + sourcePageNumber);
		}
	}
}
