package au.edu.eq.questionbank.model;

/**
 * A full-width vertical region on one page of an {@link ExamBooklet}.
 * <p>
 * Page numbers are human-readable and one-based. {@code y} is the proportional
 * distance from the displayed page's top edge and {@code height} is the
 * proportional height of the region. Both are independent of rendering DPI,
 * zoom and pixel dimensions. For example, {@code y = 0.25} and
 * {@code height = 0.50} select the full page width from one quarter to three
 * quarters of the way down the page.
 * <p>
 * Coordinates refer to the page after PDF crop-box and rotation handling. When
 * a question has multiple regions, their list order defines extraction and
 * assembly order.
 *
 * @param booklet    the booklet containing the referenced page
 * @param pageNumber the one-based page number within that booklet
 * @param y          the normalized top edge in the range {@code [0.0, 1.0)}
 * @param height     the normalized height in the range {@code (0.0, 1.0]}, with
 *                   {@code y + height <= 1.0}
 * @throws NullPointerException     if {@code booklet} is {@code null}
 * @throws IllegalArgumentException if the page number is less than one, either
 *                                  coordinate is not finite, or the region lies
 *                                  outside the normalized page bounds
 */
public record QuestionRegion(ExamBooklet booklet, int pageNumber, double x, double y, double width, double height) {

	public QuestionRegion {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (pageNumber < 1) {
			throw new IllegalArgumentException("pageNumber must be at least 1: " + pageNumber);
		}
		if (!Double.isFinite(x)) {
			throw new IllegalArgumentException("x must be finite: " + x);
		}
		if (!Double.isFinite(width)) {
			throw new IllegalArgumentException("width must be finite: " + width);
		}
		if (!Double.isFinite(y)) {
			throw new IllegalArgumentException("y must be finite: " + y);
		}
		if (!Double.isFinite(height)) {
			throw new IllegalArgumentException("height must be finite: " + height);
		}
		if (x < 0.0 || x >= 1.0) {
			throw new IllegalArgumentException("x must be in the range [0.0, 1.0): " + x);
		}
		if (y < 0.0 || y >= 1.0) {
			throw new IllegalArgumentException("y must be in the range [0.0, 1.0): " + y);
		}
		if (width <= 0.0 || width > 1.0) {
			throw new IllegalArgumentException("width must be in the range (0.0, 1.0]: " + width);
		}
		if (height <= 0.0 || height > 1.0) {
			throw new IllegalArgumentException("height must be in the range (0.0, 1.0]: " + height);
		}
		if (width > 1.0 - x) {
			throw new IllegalArgumentException("x + width must not exceed 1.0: " + (x + width));
		}
		if (height > 1.0 - y) {
			throw new IllegalArgumentException("y + height must not exceed 1.0: " + (y + height));
		}
	}
}
