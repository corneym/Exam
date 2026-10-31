package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import javafx.geometry.Rectangle2D;

class WindowGeometryTest {
	@Test
	void preservesPreferredSizeAndPositionOnLargeDisplay() {
		Rectangle2D screen = new Rectangle2D(0, 0, 2560, 1400);
		Rectangle2D request = new Rectangle2D(100, 80, 1600, 900);
		assertEquals(request, WindowGeometry.constrain(request, List.of(screen), screen));
	}

	@Test
	void fitsSmallAndScaledLogicalDisplaysIncludingTaskbar() {
		for (Rectangle2D screen : List.of(new Rectangle2D(0, 0, 1024, 728),
				new Rectangle2D(0, 40, 800, 560))) {
			assertEquals(screen, WindowGeometry.constrain(new Rectangle2D(100, 100, 1600, 1000),
					List.of(screen), screen));
		}
	}

	@Test
	void choosesGreatestOverlapIncludingNegativeMonitorCoordinates() {
		Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1040);
		Rectangle2D left = new Rectangle2D(-1280, 0, 1280, 984);
		assertEquals(new Rectangle2D(-600, 0, 600, 800), WindowGeometry.constrain(
				new Rectangle2D(-500, -10, 600, 800), List.of(primary, left), primary));
	}

	@Test
	void disconnectedMonitorFallsBackToOwnerAndClampsWholeRectangle() {
		Rectangle2D owner = new Rectangle2D(1920, 0, 1280, 984);
		Rectangle2D result = WindowGeometry.constrain(new Rectangle2D(-3000, 500, 1000, 800),
				List.of(new Rectangle2D(0, 0, 1920, 1040), owner), owner);
		assertEquals(new Rectangle2D(1920, 184, 1000, 800), result);
		assertTrue(owner.contains(result));
	}

	@Test
	void invalidSavedGeometryGetsFiniteVisibleBounds() {
		Rectangle2D screen = new Rectangle2D(0, 0, 1024, 728);
		assertEquals(screen, WindowGeometry.constrain(
				new Rectangle2D(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, 0), List.of(screen), screen));
	}
}
