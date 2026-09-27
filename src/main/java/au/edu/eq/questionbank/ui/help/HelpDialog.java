package au.edu.eq.questionbank.ui.help;

import java.net.URL;
import java.util.Objects;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.web.WebView;
import javafx.stage.Window;

/**
 * Displays the packaged Exam Question Bank user Help.
 * <p>
 * Help content is maintained as HTML and CSS under application resources rather
 * than being embedded in Java source.
 */
public final class HelpDialog extends Dialog<Void> {

	private static final String HELP_INDEX = "/au/edu/eq/questionbank/help/index.html";
	private static final double DIALOG_WIDTH = 1000;
	private static final double DIALOG_HEIGHT = 760;
	private static final double MINIMUM_WIDTH = 700;
	private static final double MINIMUM_HEIGHT = 500;
	private final WebView webView = new WebView();

	/**
	 * Creates the Help dialog owned by the supplied application window.
	 *
	 * @param owner application window that owns the Help dialog
	 * @throws NullPointerException  if {@code owner} is {@code null}
	 * @throws IllegalStateException if the packaged Help index cannot be found
	 */
	public HelpDialog(Window owner) {
		initOwner(Objects.requireNonNull(owner, "owner"));
		configureDialog();
		loadHelpIndex();
	}

	private void configureDialog() {
		setTitle("Exam Question Bank Help");
		setHeaderText(null);
		setResizable(true);
		webView.setId("help-web-view");

		// The Help viewer owns all available Dialog content space so HTML pages can
		// reflow naturally when the teacher resizes the window.
		getDialogPane().setContent(webView);
		getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
		getDialogPane().setMinWidth(MINIMUM_WIDTH);
		getDialogPane().setMinHeight(MINIMUM_HEIGHT);
		getDialogPane().setPrefWidth(DIALOG_WIDTH);
		getDialogPane().setPrefHeight(DIALOG_HEIGHT);
	}

	private void loadHelpIndex() {
		URL helpIndex = HelpDialog.class.getResource(HELP_INDEX);
		if (helpIndex == null) {

			// Missing packaged Help is an application-build error rather than a
			// recoverable user-data condition.
			throw new IllegalStateException("Packaged Help resource not found: " + HELP_INDEX);
		}

		// Loading the classpath URL lets WebView resolve relative Help links,
		// stylesheets and images from the same packaged resource directory.
		webView.getEngine().load(helpIndex.toExternalForm());
	}
}
