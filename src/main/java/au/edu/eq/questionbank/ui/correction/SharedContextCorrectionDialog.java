package au.edu.eq.questionbank.ui.correction;

import java.util.Objects;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.repository.assessment.SqliteSharedContextCorrectionService.Review;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Reviews the members of an existing multipart SourceQuestion and obtains a
 * deliberate Shared Context correction decision.
 *
 * No persistence occurs inside this dialog.
 */
public final class SharedContextCorrectionDialog extends Dialog<SharedContextCorrectionDialog.Decision> {

	private final ButtonType continueButtonType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
	private final ComboBox<SharedQuestionContext> existingContextBox = new ComboBox<>();
	private final RadioButton newContextButton = new RadioButton("Capture new Shared Context from the Question PDF");
	private final RadioButton reuseContextButton = new RadioButton("Use an existing Shared Context from this booklet");
	private final Review review;

	/**
	 * Creates the correction decision dialog.
	 *
	 * @param owner  owning window
	 * @param review authoritative source-group review
	 */
	public SharedContextCorrectionDialog(Window owner, Review review) {
		this.review = Objects.requireNonNull(review, "review");
		initOwner(Objects.requireNonNull(owner, "owner"));
		setTitle("Correct Shared Context");
		setHeaderText("Review all affected Question parts");
		setResizable(true);
		configureControls();
		getDialogPane().setContent(createContent());
		getDialogPane().getButtonTypes().setAll(continueButtonType, ButtonType.CANCEL);
		configureContinueButton();
		setResultConverter(this::convertResult);
		getDialogPane().setPrefWidth(650);
	}

	private void configureContinueButton() {
		Button continueButton = (Button) getDialogPane().lookupButton(continueButtonType);
		continueButton.setId("shared-context-correction-continue");
		continueButton.disableProperty()
				.bind(Bindings.createBooleanBinding(
						() -> reuseContextButton.isSelected() && existingContextBox.getValue() == null,
						reuseContextButton.selectedProperty(), existingContextBox.valueProperty()));
	}

	private void configureControls() {
		ToggleGroup choiceGroup = new ToggleGroup();
		newContextButton.setToggleGroup(choiceGroup);
		reuseContextButton.setToggleGroup(choiceGroup);
		newContextButton.setId("shared-context-correction-new");
		reuseContextButton.setId("shared-context-correction-reuse");
		newContextButton.setSelected(true);
		reuseContextButton.setDisable(review.availableContexts().isEmpty());
		existingContextBox.setId("shared-context-correction-existing-context");
		existingContextBox.getItems().setAll(review.availableContexts());
		existingContextBox.setPromptText("Select a booklet Shared Context");
		existingContextBox.setMaxWidth(Double.MAX_VALUE);
		existingContextBox.disableProperty().bind(reuseContextButton.selectedProperty().not());
		existingContextBox.setConverter(new StringConverter<>() {

			@Override
			public SharedQuestionContext fromString(String text) {
				return null;
			}

			@Override
			public String toString(SharedQuestionContext context) {
				if (context == null) {
					return "";
				}
				return context.getLabel() + " (" + context.getRegions().size() + " region(s))";
			}
		});
	}

	private Decision convertResult(ButtonType buttonType) {
		if (buttonType != continueButtonType) {
			return null;
		}
		if (newContextButton.isSelected()) {
			return new Decision(Mode.CAPTURE_NEW, 0);
		}
		SharedQuestionContext selected = existingContextBox.getValue();
		if (selected == null) {
			return null;
		}
		return new Decision(Mode.REUSE_EXISTING, selected.getId());
	}

	private VBox createContent() {
		Label source = new Label("Source Question: " + review.sourceQuestion().getSourceQuestionCode());
		Label state = new Label("Current Shared Context: " + review.sourceQuestion().getSharedContextStatus().name());
		Label booklet = new Label("Booklet: " + review.sourceQuestion().getBooklet().getName());
		VBox affectedParts = new VBox(4);
		affectedParts.setId("shared-context-correction-parts");
		for (Question part : review.parts()) {
			affectedParts.getChildren()
					.add(new Label(part.getQuestionCode() + " — " + part.getMarks() + " mark(s), ID " + part.getId()));
		}
		ScrollPane partsScroll = new ScrollPane(affectedParts);
		partsScroll.setFitToWidth(true);
		partsScroll.setPrefViewportHeight(Math.min(6, review.parts().size()) * 28.0 + 8.0);
		partsScroll.setMaxHeight(200);
		VBox content = new VBox(10, booklet, source, state, new Label("All affected Question parts:"), partsScroll,
				newContextButton, reuseContextButton, existingContextBox);
		if (review.availableContexts().isEmpty()) {
			content.getChildren()
					.add(new Label("No previously captured Shared Context " + "is available in this booklet."));
		}
		content.setPadding(new Insets(12));
		return content;
	}

	/**
	 * Correction operation selected by the user.
	 */
	public enum Mode {

		/** Capture a new Shared Context from the source PDF. */
		CAPTURE_NEW,

		/** Reuse an existing Shared Context from the booklet. */
		REUSE_EXISTING
	}

	/**
	 * Confirmed correction choice.
	 *
	 * @param mode      selected operation
	 * @param contextId existing context ID for reuse, otherwise zero
	 */
	public record Decision(Mode mode, long contextId) {

		/**
		 * Validates the selected correction decision.
		 *
		 * @throws NullPointerException     if mode is null
		 * @throws IllegalArgumentException if the context ID is invalid for the
		 *                                  selected mode
		 */
		public Decision {
			Objects.requireNonNull(mode, "mode");
			if (mode == Mode.REUSE_EXISTING && contextId < 1) {
				throw new IllegalArgumentException("Existing Shared Context ID is required");
			}
			if (mode == Mode.CAPTURE_NEW && contextId != 0) {
				throw new IllegalArgumentException("New Shared Context must not have an existing ID");
			}
		}
	}
}
