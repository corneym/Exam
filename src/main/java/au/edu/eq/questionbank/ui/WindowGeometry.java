package au.edu.eq.questionbank.ui;

import java.util.List;

import javafx.geometry.Rectangle2D;

/** Pure geometry calculations in JavaFX logical screen coordinates. */
public final class WindowGeometry {

	private WindowGeometry() {
	}

	/**
	 * Fits a requested rectangle to the screen with greatest overlap, falling back
	 * to the supplied screen when the saved position is no longer visible.
	 *
	 * @param requested requested window bounds, possibly containing invalid coordinates
	 * @param screens current usable screen bounds
	 * @param fallback owner or primary screen bounds
	 * @return a fully visible rectangle without enlarging a valid requested size
	 */
	public static Rectangle2D constrain(Rectangle2D requested, List<Rectangle2D> screens, Rectangle2D fallback) {
		Rectangle2D screen = screenFor(requested, screens, fallback);
		double width = dimension(requested.getWidth(), screen.getWidth());
		double height = dimension(requested.getHeight(), screen.getHeight());
		double x = coordinate(requested.getMinX(), screen.getMinX(), screen.getMaxX() - width);
		double y = coordinate(requested.getMinY(), screen.getMinY(), screen.getMaxY() - height);
		return new Rectangle2D(x, y, width, height);
	}

	static Rectangle2D screenFor(Rectangle2D requested, List<Rectangle2D> screens, Rectangle2D fallback) {
		Rectangle2D selected = fallback;
		double greatestOverlap = 0;
		for (Rectangle2D screen : screens) {
			double width = Math.max(0, Math.min(requested.getMaxX(), screen.getMaxX())
					- Math.max(requested.getMinX(), screen.getMinX()));
			double height = Math.max(0, Math.min(requested.getMaxY(), screen.getMaxY())
					- Math.max(requested.getMinY(), screen.getMinY()));
			if (width * height > greatestOverlap) {
				greatestOverlap = width * height;
				selected = screen;
			}
		}
		return selected;
	}

	private static double coordinate(double value, double minimum, double maximum) {
		return Double.isFinite(value) ? Math.max(minimum, Math.min(value, maximum)) : (minimum + maximum) / 2;
	}

	private static double dimension(double value, double available) {
		return Double.isFinite(value) && value > 0 ? Math.min(value, available) : available;
	}
}
