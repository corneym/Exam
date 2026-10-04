package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;

/**
 * Reads a raster image from the JavaFX system clipboard and converts it to PNG
 * bytes suitable for persistent Question content.
 * <p>
 * This supports images placed on the clipboard by tools such as Windows
 * Snipping Tool without introducing JavaFX image objects into the domain model.
 */
public final class QuestionClipboardImageReader {

	private final BooleanSupplier imageAvailable;
	private final Supplier<Image> imageReader;

	/**
	 * Creates a reader backed by the JavaFX system clipboard.
	 */
	public QuestionClipboardImageReader() {

		// Keep platform clipboard access behind small suppliers so Windows clipboard
		// decoder failures can be contained at this application boundary.
		this(() -> Clipboard.getSystemClipboard().hasImage(), () -> Clipboard.getSystemClipboard().getImage());
	}

	QuestionClipboardImageReader(BooleanSupplier imageAvailable, Supplier<Image> imageReader) {
		this.imageAvailable = Objects.requireNonNull(imageAvailable, "imageAvailable");
		this.imageReader = Objects.requireNonNull(imageReader, "imageReader");
	}

	/**
	 * Reports whether the system clipboard currently exposes readable image
	 * content.
	 *
	 * @return {@code true} when JavaFX can read an image from the clipboard
	 */
	public boolean hasImage() {
		try {

			// JavaFX normally performs the platform-specific conversion, including
			// native image formats placed on the clipboard by Windows Snipping Tool.
			return imageAvailable.getAsBoolean();
		} catch (RuntimeException exception) {

			// The Windows JavaFX clipboard decoder can throw while inspecting unrelated
			// or malformed clipboard formats. Availability is advisory only, so an
			// unreadable clipboard is equivalent to there being no usable image.
			return false;
		}
	}

	/**
	 * Reads the current clipboard image and encodes it as PNG.
	 *
	 * @return encoded PNG bytes, or empty when the clipboard contains no readable
	 *         image
	 * @throws IOException if an advertised clipboard image cannot be read or
	 *                     encoded as PNG
	 */
	public Optional<byte[]> readPng() throws IOException {
		if (!hasImage()) {

			// An unavailable or temporarily unreadable clipboard must not interrupt
			// Question capture.
			return Optional.empty();
		}
		Image image;
		try {
			image = imageReader.get();
		} catch (RuntimeException exception) {

			// Actual capture is an explicit operation, so convert platform decoder
			// failure into the checked error already handled by the capture workflow.
			throw new IOException("Clipboard image could not be read", exception);
		}
		if (image == null) {
			return Optional.empty();
		}
		BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
		if (bufferedImage == null) {
			throw new IOException("Clipboard image could not be converted to a raster image");
		}
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {

			// Persist one predictable lossless representation regardless of the
			// clipboard's original platform-specific image format.
			if (!ImageIO.write(bufferedImage, "png", output)) {
				throw new IOException("No PNG writer is available");
			}
			byte[] pngBytes = output.toByteArray();
			if (pngBytes.length == 0) {
				throw new IOException("Clipboard image produced an empty PNG");
			}
			return Optional.of(pngBytes);
		}
	}
}
