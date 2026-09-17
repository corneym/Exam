package au.edu.eq.questionbank.model;

/**
 * One ordered rectangular source region belonging to shared question context.
 *
 * @param pageNumber one-based page number within the owning context's booklet
 * @param x          normalized left edge
 * @param y          normalized top edge
 * @param width      normalized width
 * @param height     normalized height
 */
public record SharedQuestionContextRegion(int pageNumber, double x, double y, double width, double height) {

	/**
	 * Validates a one-based page and proportional bounds for a shared-context region.
	 *
	 * @param pageNumber one-based page number within the owning context's booklet
	 * @param x          normalized left edge
	 * @param y          normalized top edge
	 * @param width      normalized width
	 * @param height     normalized height
	 */
	public SharedQuestionContextRegion {
		if (pageNumber < 1) {
			throw new IllegalArgumentException("pageNumber must be at least 1: " + pageNumber);
		}
		// Reject non-finite values before bounds comparisons, which alone would not reject NaN.
		if (!Double.isFinite(x)) {
			throw new IllegalArgumentException("x must be finite: " + x);
		}
		if (!Double.isFinite(y)) {
			throw new IllegalArgumentException("y must be finite: " + y);
		}
		if (!Double.isFinite(width)) {
			throw new IllegalArgumentException("width must be finite: " + width);
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
