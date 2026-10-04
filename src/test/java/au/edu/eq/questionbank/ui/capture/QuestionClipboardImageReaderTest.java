package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class QuestionClipboardImageReaderTest {

	@Test
	void clipboardAvailabilityFailureIsTreatedAsNoImage() throws Exception {
		QuestionClipboardImageReader reader = new QuestionClipboardImageReader(() -> {

			// Reproduce the RuntimeException that JavaFX on Windows may throw while
			// decoding malformed or unrelated native clipboard data.
			throw new RuntimeException("Unexpected IOException caught");
		}, () -> {
			throw new AssertionError("Image data must not be requested after availability failed");
		});

		// Focus-driven availability checks are advisory and must never propagate a
		// platform clipboard decoder failure onto the JavaFX application thread.
		assertFalse(reader.hasImage());
		assertTrue(reader.readPng().isEmpty());
	}

	@Test
	void clipboardImageReadFailureBecomesCheckedIOException() {
		RuntimeException platformFailure = new RuntimeException("Unexpected IOException caught");
		QuestionClipboardImageReader reader = new QuestionClipboardImageReader(() -> true, () -> {

			// Availability can succeed even if the subsequent native image transfer
			// fails, so preserve that failure as the cause of the checked read error.
			throw platformFailure;
		});
		IOException failure = assertThrows(IOException.class, reader::readPng);

		// Explicit image capture receives the existing checked failure contract rather
		// than leaking a JavaFX platform RuntimeException.
		assertInstanceOf(RuntimeException.class, failure.getCause());
		assertTrue(failure.getMessage().contains("Clipboard image could not be read"));
	}
}
