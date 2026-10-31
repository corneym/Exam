package au.edu.eq.questionbank.ui.curriculum;

import au.edu.eq.questionbank.model.CurriculumNode;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;

/** Shared presentation for classification selectors that can contain long Descriptors. */
public final class DescriptorComboBoxSupport {
	private DescriptorComboBoxSupport() {
	}

	/**
	 * Keeps the selected control compact and supplies a bounded, wrapping popup.
	 * Existing selection handlers and hierarchy filtering remain owned by the caller.
	 *
	 * @param box classification selector to configure
	 */
	public static void configure(ComboBox<CurriculumNode> box) {
		box.setMinWidth(0);
		box.setPrefWidth(240);
		box.setSkin(new DescriptorComboBoxSkin(box));
		String originalTooltip = box.getTooltip() == null ? "" : box.getTooltip().getText();
		Tooltip tooltip = new Tooltip(originalTooltip);
		tooltip.setWrapText(true);
		tooltip.setMaxWidth(600);
		box.setTooltip(tooltip);
		box.valueProperty().addListener((_, _, value) -> tooltip.setText(value == null ? originalTooltip
				: value.toString() + (originalTooltip.isBlank() ? "" : "\n\n" + originalTooltip)));
	}
}
