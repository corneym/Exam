package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import javafx.beans.property.SimpleBooleanProperty;

class FocusRefreshRegistrationTest {

	@Test
	void repeatedRegistrationsDoNotRetainEarlierFocusCallbacks() {
		SimpleBooleanProperty focused = new SimpleBooleanProperty(false);
		List<AtomicInteger> callbackCounts = new ArrayList<>();
		for (int cycle = 0; cycle < 10; cycle++) {
			AtomicInteger callbacks = new AtomicInteger();
			callbackCounts.add(callbacks);
			FocusRefreshRegistration registration = new FocusRefreshRegistration(focused, callbacks::incrementAndGet);

			// Gaining focus invokes only the current registration.
			focused.set(true);
			assertEquals(1, callbacks.get());

			// Losing focus must not refresh clipboard availability.
			focused.set(false);
			assertEquals(1, callbacks.get());

			// Shutdown removes the listener. Repeated cleanup is harmless.
			registration.close();
			registration.close();
			focused.set(true);
			focused.set(false);

			// All registrations from earlier cycles must remain inactive.
			for (AtomicInteger count : callbackCounts) {
				assertEquals(1, count.get());
			}
		}
	}
}
