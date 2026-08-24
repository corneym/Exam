package au.edu.eq.questionbank.model;

/**
 * A rectangular region of a source PDF page, expressed independently of any
 * rendering resolution.
 * <p>
 * Page numbers are human-readable and one-based. The coordinates use a top-left
 * origin and are normalized fractions of the rendered page: {@code x} and
 * {@code y} locate the top-left corner, while {@code width} and {@code height}
 * describe the region's extent. For example, {@code x = 0.10} means ten percent
 * of the rendered page width from its left edge.
 * <p>
 * Coordinates refer to the page as displayed after the renderer has applied the
 * PDF page's crop box and rotation. They do not represent PDF user-space
 * points, screen coordinates, zoom levels, DPI, or pixels. When a question has
 * multiple regions, their order in the question's region list defines their
 * extraction and assembly order.
 *
 * @param booklet    the exam booklet holding the page and region data
 * @param pageNumber the one-based source PDF page number
 * @param x          the normalized horizontal position of the region's left
 *                   edge
 * @param y          the normalized vertical position of the region's top edge
 * @param width      the normalized width of the region
 * @param height     the normalized height of the region
 * @throws IllegalArgumentException if the page number is less than one, a
 *                                  coordinate is not finite, or the region has
 *                                  no positive area within the normalized page
 *                                  bounds
 */
public record QuestionRegion(ExamBooklet booklet, int pageNumber, double y, double height) {

	public QuestionRegion {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (pageNumber < 1) {
			throw new IllegalArgumentException("pageNumber must be at least 1: " + pageNumber);
		}
		if (!Double.isFinite(y)) {
			throw new IllegalArgumentException("y must be finite: " + y);
		}
		if (!Double.isFinite(height)) {
			throw new IllegalArgumentException("height must be finite: " + height);
		}
		if (y < 0.0 || y >= 1.0) {
			throw new IllegalArgumentException("y must be in the range [0.0, 1.0): " + y);
		}
		if (height <= 0.0 || height > 1.0) {
			throw new IllegalArgumentException("height must be in the range (0.0, 1.0]: " + height);
		}
		if (height > 1.0 - y) {
			throw new IllegalArgumentException("y + height must not exceed 1.0: " + (y + height));
		}
	}
}
