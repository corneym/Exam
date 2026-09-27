package au.edu.eq.questionbank.ui.search;

import java.util.function.Function;
import java.util.function.Supplier;

import javafx.scene.control.ListCell;

/**
 * List cell that renders an item through a supplied text function.
 * <p>
 * An optional empty-text supplier supports ComboBox button cells that must
 * continue displaying the control's prompt while no value is selected.
 *
 * @param <T> item type displayed by the cell
 */
final class DisplayListCell<T> extends ListCell<T> {

	private final Function<T, String> displayText;
	private final Supplier<String> emptyText;

	DisplayListCell(Function<T, String> displayText) {
		this(displayText, () -> null);
	}

	DisplayListCell(Function<T, String> displayText, Supplier<String> emptyText) {
		if (displayText == null) {
			throw new NullPointerException("displayText");
		}
		if (emptyText == null) {
			throw new NullPointerException("emptyText");
		}
		this.displayText = displayText;
		this.emptyText = emptyText;
	}

	@Override
	protected void updateItem(T item, boolean empty) {
		super.updateItem(item, empty);

		// Empty ComboBox button cells may deliberately display prompt text, while
		// ordinary ListView cells normally supply null here.
		if (empty || item == null) {
			setText(emptyText.get());
			return;
		}
		setText(displayText.apply(item));
	}
}
