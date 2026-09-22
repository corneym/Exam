package au.edu.eq.questionbank.model;

import java.util.List;

/**
 * Reusable introductory source material required by one or more questions.
 * <p>
 * Examples include a shared context, common stem, graph, table or diagram. A
 * shared context belongs to one examination booklet and contains one or more
 * ordered source regions.
 */
public final class SharedQuestionContext {

	private final long id;
	private final ExamBooklet booklet;
	private final String label;
	private final List<SharedQuestionContextRegion> regions;

	/**
	 * Creates a persisted shared context from its ordered source regions.
	 *
	 * @param id      the positive persistent identifier
	 * @param booklet the examination booklet containing every region
	 * @param label   the non-blank display label
	 * @param regions one or more regions in source order; the list is copied
	 * @throws IllegalArgumentException if {@code id} is not positive, the label is
	 *                                  blank, or no regions are supplied
	 * @throws NullPointerException     if the booklet, region list or an element is
	 *                                  {@code null}
	 */
	public SharedQuestionContext(long id, ExamBooklet booklet, String label,
			List<SharedQuestionContextRegion> regions) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}

		// A shared context represents captured material, so an empty placeholder is not
		// valid.
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("Shared question context must contain at least one region");
		}
		for (SharedQuestionContextRegion region : regions) {
			if (region == null) {
				throw new NullPointerException("regions contains null");
			}
		}
		this.id = id;
		this.booklet = booklet;
		this.label = label;

		// All questions sharing this context see the same immutable region order.
		this.regions = List.copyOf(regions);
	}

	/**
	 * Returns the booklet containing this reusable shared context.
	 *
	 * @return the booklet that owns this shared context
	 */
	public ExamBooklet getBooklet() {
		return booklet;
	}

	/**
	 * Returns the persistent identity of this shared context.
	 *
	 * @return the positive persistent identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the label used to identify this shared context during capture.
	 *
	 * @return the display label
	 */
	public String getLabel() {
		return label;
	}

	/**
	 * Returns an immutable list in source and rendering order.
	 *
	 * @return the ordered shared-context regions
	 */
	public List<SharedQuestionContextRegion> getRegions() {
		return regions;
	}
}
