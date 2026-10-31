package au.edu.eq.questionbank.ui;

import java.util.List;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Applies one fit-to-screen policy to application stages, including dialog stages. */
public final class ScreenAwareWindows implements AutoCloseable {

	private final ListChangeListener<Window> listener = change -> {
		while (change.next()) {
			for (Window window : change.getAddedSubList()) {
				if (window instanceof Stage stage) {
					// Native decorations and Dialog preferred sizing settle after showing.
					Platform.runLater(() -> fit(stage));
				}
			}
		}
	};

	/** Registers the policy before the first application or startup-error window opens. */
	public ScreenAwareWindows() {
		Window.getWindows().addListener(listener);
	}

	@Override
	public void close() {
		Window.getWindows().removeListener(listener);
	}

	/** Fits a showing stage using current visual bounds, including taskbar and scaling. */
	public static void fit(Stage stage) {
		if (!stage.isShowing() || stage.getScene() == null) {
			return;
		}
		List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
		Rectangle2D fallback = Screen.getPrimary().getVisualBounds();
		if (stage.getOwner() != null) {
			fallback = WindowGeometry.screenFor(bounds(stage.getOwner()), screens, fallback);
		}
		fit(stage, screens, fallback);
	}

	/** Returns the usable monitor bounds for a window, or the primary screen if absent. */
	public static Rectangle2D visualBounds(Window window) {
		Rectangle2D primary = Screen.getPrimary().getVisualBounds();
		return window == null ? primary : WindowGeometry.screenFor(bounds(window),
				Screen.getScreens().stream().map(Screen::getVisualBounds).toList(), primary);
	}

	static void fit(Stage stage, List<Rectangle2D> screens, Rectangle2D fallback) {
		Rectangle2D requested = bounds(stage);
		Rectangle2D screen = WindowGeometry.screenFor(requested, screens, fallback);
		Rectangle2D fitted = WindowGeometry.constrain(requested, screens, fallback);
		stage.setMinWidth(Math.min(stage.getMinWidth(), screen.getWidth()));
		stage.setMinHeight(Math.min(stage.getMinHeight(), screen.getHeight()));
		if (fitted.getWidth() < requested.getWidth() || fitted.getHeight() < requested.getHeight()) {
			makeOverflowAccessible(stage.getScene());
		}
		stage.setWidth(fitted.getWidth());
		stage.setHeight(fitted.getHeight());
		stage.setX(fitted.getMinX());
		stage.setY(fitted.getMinY());
	}

	private static Rectangle2D bounds(Window window) {
		return new Rectangle2D(window.getX(), window.getY(), Math.max(0, window.getWidth()),
				Math.max(0, window.getHeight()));
	}

	private static void makeOverflowAccessible(Scene scene) {
		if (scene.getRoot().getProperties().containsKey(ScreenAwareWindows.class)) {
			return;
		}
		Parent content = scene.getRoot();
		ScrollPane viewport = new ScrollPane();
		viewport.getProperties().put(ScreenAwareWindows.class, Boolean.TRUE);
		viewport.setFitToWidth(true);
		viewport.setFitToHeight(true);
		viewport.setMinSize(0, 0);
		// Keep the existing content and its functional minimum sizes. Scrollbars
		// expose controls when a small screen cannot accommodate that minimum.
		scene.setRoot(viewport);
		viewport.setContent(content);
		if (content instanceof Region region) {
			region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		}
	}
}
