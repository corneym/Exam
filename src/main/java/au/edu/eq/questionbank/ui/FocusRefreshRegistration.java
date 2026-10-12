package au.edu.eq.questionbank.ui;

import java.util.Objects;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;

final class FocusRefreshRegistration {

	private final ObservableValue<Boolean> focusedProperty;
	private final ChangeListener<Boolean> listener;
	private boolean removed;

	FocusRefreshRegistration(ObservableValue<Boolean> focusedProperty, Runnable refreshAction) {
		this.focusedProperty = Objects.requireNonNull(focusedProperty);
		Objects.requireNonNull(refreshAction);
		listener = (_, _, focused) -> {
			if (Boolean.TRUE.equals(focused)) {
				refreshAction.run();
			}
		};
		focusedProperty.addListener(listener);
	}

	void close() {
		if (removed) {
			return;
		}
		focusedProperty.removeListener(listener);
		removed = true;
	}
}
