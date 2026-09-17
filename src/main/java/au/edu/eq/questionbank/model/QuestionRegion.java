package au.edu.eq.questionbank.model;

/**
 * A rectangular region on one page of an {@link ExamBooklet}.
 * <p>
 * Page numbers are human-readable and one-based. Coordinates are proportional
 * to the displayed page: {@code x} and {@code y} locate the top-left corner,
 * while {@code width} and {@code height} describe the rectangle's extent. They
 * are independent of rendering DPI, zoom, and pixel dimensions. For example,
 * {@code x = 0.25} and {@code width = 0.50} select the middle half of the page
 * horizontally.
 * <p>
 * Coordinates refer to the page after PDF crop-box and rotation handling. When
 * a question has multiple regions, their list order defines extraction and
 * assembly order.
 *
 * @param booklet    the booklet containing the referenced page
 * @param pageNumber the one-based page number within that booklet
 * @param x          the normalized left edge in the range {@code [0.0, 1.0)}
 * @param y          the normalized top edge in the range {@code [0.0, 1.0)}
 * @param width      the normalized width in the range {@code (0.0, 1.0]}, with
 *                   {@code x + width <= 1.0}
 * @param height     the normalized height in the range {@code (0.0, 1.0]}, with
 *                   {@code y + height <= 1.0}
 */
public record QuestionRegion(ExamBooklet booklet, int pageNumber, double x, double y, double width, double height) {

	/**
	 * Creates and validates this value.
	 *
	 * @throws NullPointerException     if {@code booklet} is {@code null}
	 * @throws IllegalArgumentException if the page number is less than one, a
	 *                                  coordinate or dimension is not finite, or the
	 *                                  region lies outside the normalized page
	 *                                  bounds
	 */
	public QuestionRegion {
		if (booklet == null) {
			throw new NullPointerException("booklet");
		}
		if (pageNumber < 1) {
			throw new IllegalArgumentException("pageNumber must be at least 1: " + pageNumber);
		}
		// Reject non-finite values before bounds comparisons, which alone would not reject NaN.
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
		// Require an origin inside the normalized page and strictly positive dimensions.
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
		// Compare each extent with the remaining page space so the far edges stay within the page.
		if (width > 1.0 - x) {
			throw new IllegalArgumentException("x + width must not exceed 1.0: " + (x + width));
		}
		if (height > 1.0 - y) {
			throw new IllegalArgumentException("y + height must not exceed 1.0: " + (y + height));
		}
	}
}
