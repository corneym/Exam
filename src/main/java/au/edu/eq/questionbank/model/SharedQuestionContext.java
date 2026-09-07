package au.edu.eq.questionbank.model;

import java.util.List;

/**
 * Reusable introductory source material required by one or more questions.
 * <p>
 * Examples include a preamble, common stem, graph, table or diagram. A shared
 * context belongs to one examination booklet and contains one or more ordered
 * source regions.
 */
public final class SharedQuestionContext {

	private final long id;
	private final ExamBooklet booklet;
	private final String label;
	private final List<SharedQuestionContextRegion> regions;

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
		this.regions = List.copyOf(regions);
	}

	public ExamBooklet getBooklet() {
		return booklet;
	}

	public long getId() {
		return id;
	}

	public String getLabel() {
		return label;
	}

	public List<SharedQuestionContextRegion> getRegions() {
		return regions;
	}
}
