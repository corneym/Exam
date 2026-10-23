package au.edu.eq.questionbank.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Reusable introductory source material required by one or more questions.
 * <p>
 * A Shared Context belongs to one examination booklet and contains one or more
 * ordered PDF regions, PNG images, or both.
 */
public final class SharedQuestionContext {

	private final ExamBooklet booklet;
	private final List<SharedContextContentPart> contentParts;
	private final long id;
	private final String label;
	private final List<SharedQuestionContextRegion> regions;

	/**
	 * Creates a persisted PDF-only Shared Context.
	 * <p>
	 * This constructor preserves compatibility with existing capture, repository
	 * and correction workflows.
	 *
	 * @param id      the positive persistent identifier
	 * @param booklet the owning examination booklet
	 * @param label   the non-blank display label
	 * @param regions one or more PDF regions in source order
	 * @throws IllegalArgumentException if the identifier, label or region
	 *                                  collection is invalid
	 * @throws NullPointerException     if any required value is null
	 */
	public SharedQuestionContext(long id, ExamBooklet booklet, String label,
			List<SharedQuestionContextRegion> regions) {
		this(id, booklet, label, copyRegionsAsContent(regions));
	}

	private SharedQuestionContext(long id, ExamBooklet booklet, String label,
			Collection<? extends SharedContextContentPart> parts) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		this.booklet = Objects.requireNonNull(booklet, "booklet");
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		this.label = label;
		Objects.requireNonNull(parts, "parts");
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("Shared question context must contain at least one content part");
		}

		// The immutable mixed-content list is authoritative. PDF-only
		// consumers receive a derived projection preserving relative
		// region order, without changing or discarding image positions
		// in the complete content sequence.
		this.contentParts = List.copyOf(parts);
		List<SharedQuestionContextRegion> pdfRegions = new ArrayList<>();
		for (SharedContextContentPart part : contentParts) {
			if (part instanceof SharedQuestionContextRegion region) {
				pdfRegions.add(region);
			}
		}
		this.regions = List.copyOf(pdfRegions);
		this.id = id;
	}

	/**
	 * Creates a persisted Shared Context containing ordered PDF regions, PNG
	 * images, or both.
	 *
	 * @param id           the positive persistent identifier
	 * @param booklet      the owning examination booklet
	 * @param label        the non-blank display label
	 * @param contentParts one or more content parts in display order
	 * @return the immutable Shared Context
	 * @throws IllegalArgumentException if the identifier, label or content
	 *                                  collection is invalid
	 * @throws NullPointerException     if any required value is null
	 */
	public static SharedQuestionContext fromContentParts(long id, ExamBooklet booklet, String label,
			List<? extends SharedContextContentPart> contentParts) {
		return new SharedQuestionContext(id, booklet, label, contentParts);
	}

	private static List<SharedContextContentPart> copyRegionsAsContent(List<SharedQuestionContextRegion> regions) {
		Objects.requireNonNull(regions, "regions");
		List<SharedContextContentPart> parts = new ArrayList<>(regions.size());
		parts.addAll(regions);
		return List.copyOf(parts);
	}

	/**
	 * Returns the owning examination booklet.
	 *
	 * @return the booklet
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * Returns all Shared Context fragments in authoritative display order.
	 *
	 * @return an immutable ordered list of PDF and image content parts
	 */
	public List<SharedContextContentPart> getContentParts() {
		return contentParts;
	}

	/**
	 * Returns the persistent identifier.
	 *
	 * @return the positive identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the display label.
	 *
	 * @return the Shared Context label
	 */
	public String getLabel() {
		return label;
	}

	/**
	 * Returns the PDF-region projection of this Shared Context.
	 * <p>
	 * This compatibility method excludes image-backed parts and must not be used to
	 * reconstruct the complete content order.
	 *
	 * @return immutable PDF regions in their original relative order
	 */
	public List<SharedQuestionContextRegion> getRegions() {
		return regions;
	}
}
