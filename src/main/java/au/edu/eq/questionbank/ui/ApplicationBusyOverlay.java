package au.edu.eq.questionbank.ui;

import java.util.Objects;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Reusable application-wide busy presentation.
 *
 * An accepted operation immediately disables the underlying application. The
 * visual overlay appears only after a short delay so quick operations do not
 * cause a distracting spinner flash.
 *
 * Operations receive a generation token. A stale completion cannot dismiss the
 * overlay belonging to a newer operation.
 */
public final class ApplicationBusyOverlay extends StackPane {

	private static final Duration DEFAULT_DISPLAY_DELAY = Duration.millis(180);
	private final PauseTransition appearanceDelay;
	private final StackPane busyShield = new StackPane();
	private final Node content;
	private final Label statusLabel = new Label();
	private long activeRequest;
	private long nextRequest;

	/**
	 * Creates an overlay around the complete application content.
	 *
	 * @param content the application root to protect
	 */
	public ApplicationBusyOverlay(Node content) {
		this(content, DEFAULT_DISPLAY_DELAY);
	}

	ApplicationBusyOverlay(Node content, Duration displayDelay) {
		this.content = Objects.requireNonNull(content, "content");
		Objects.requireNonNull(displayDelay, "displayDelay");
		if (displayDelay.lessThan(Duration.ZERO) || displayDelay.isIndefinite() || displayDelay.isUnknown()) {
			throw new IllegalArgumentException("displayDelay must be finite and non-negative");
		}
		setId("application-busy-root");
		ProgressIndicator indicator = new ProgressIndicator();
		indicator.setId("workspace-busy-indicator");
		indicator.setMaxSize(48, 48);
		indicator.setPrefSize(48, 48);
		statusLabel.setId("workspace-busy-label");
		statusLabel.setWrapText(true);
		statusLabel.setAlignment(Pos.CENTER);
		statusLabel.setMaxWidth(Double.MAX_VALUE);
		statusLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
		VBox panel = new VBox(16, indicator, statusLabel);
		panel.setId("application-busy-panel");
		panel.setAlignment(Pos.CENTER);
		panel.setPadding(new Insets(24));
		panel.setPrefWidth(360);
		panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		panel.setStyle("""
				-fx-background-color: white;
				-fx-background-radius: 10;
				-fx-border-radius: 10;
				-fx-effect: dropshadow(gaussian,
				    rgba(0,0,0,0.22), 18, 0.15, 0, 4);
				""");
		busyShield.setId("workspace-busy-overlay");
		busyShield.setAlignment(Pos.CENTER);
		busyShield.setStyle("-fx-background-color: rgba(15,23,42,0.34);");
		busyShield.setPickOnBounds(true);
		busyShield.getChildren().add(panel);
		busyShield.setVisible(false);
		busyShield.managedProperty().bind(busyShield.visibleProperty());
		getChildren().setAll(content, busyShield);
		appearanceDelay = new PauseTransition(displayDelay);
		appearanceDelay.setOnFinished(_ -> {

			// A cancelled or completed operation cannot reveal
			// a stale overlay after its delay expires.
			if (activeRequest != 0) {
				busyShield.setVisible(true);
				busyShield.toFront();
			}
		});
	}

	/**
	 * Begins an application-wide operation.
	 *
	 * The underlying application becomes inactive immediately. A return value of
	 * zero means another operation already owns the overlay.
	 *
	 * @param message operation-specific progress message
	 * @return the accepted operation token, or zero when busy
	 */
	public long begin(String message) {
		Objects.requireNonNull(message, "message");
		if (message.isBlank()) {
			throw new IllegalArgumentException("Busy message must not be blank");
		}
		if (activeRequest != 0) {
			return 0;
		}
		activeRequest = ++nextRequest;
		statusLabel.setText(message);

		// Logical busy state begins before the optional
		// visual display delay.
		content.setDisable(true);
		appearanceDelay.playFromStart();
		return activeRequest;
	}

	/**
	 * Cancels the currently active operation.
	 *
	 * Application navigation and shutdown may use this method when the previous
	 * workflow is no longer authoritative.
	 */
	public void cancel() {
		finish(activeRequest);
	}

	/**
	 * Finishes an operation when its token still owns the overlay.
	 *
	 * @param request operation token returned by begin
	 */
	public void finish(long request) {
		if (request == 0 || request != activeRequest) {
			return;
		}
		activeRequest = 0;
		appearanceDelay.stop();
		busyShield.setVisible(false);
		statusLabel.setText("");
		content.setDisable(false);
	}

	/**
	 * Returns whether an operation currently owns the application.
	 *
	 * @return true even while visual display is still delayed
	 */
	public boolean isBusy() {
		return activeRequest != 0;
	}

	/**
	 * Determines whether a request still owns the application busy state.
	 *
	 * @param request token returned by begin
	 * @return true only when the request is current
	 */
	public boolean owns(long request) {
		return request != 0 && request == activeRequest;
	}
}
