package au.edu.eq.questionbank.model;

/**
 * A rectangular region on one page of an {@link AnswerFile}.
 *
 * @param answerFile the answer file containing the referenced page
 * @param pageNumber the one-based page number within that file
 * @param x          the normalized left edge in the range {@code [0.0, 1.0)}
 * @param y          the normalized top edge in the range {@code [0.0, 1.0)}
 * @param width      the normalized width in the range {@code (0.0, 1.0]}
 * @param height     the normalized height in the range {@code (0.0, 1.0]}
 */
public record AnswerRegion(AnswerFile answerFile, int pageNumber, double x, double y, double width, double height) {

	public AnswerRegion {
		if (answerFile == null) {
			throw new NullPointerException("answerFile");
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