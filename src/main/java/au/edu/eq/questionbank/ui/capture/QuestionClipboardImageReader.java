package au.edu.eq.questionbank.ui.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

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

	/**
	 * Creates a clipboard image reader.
	 */
	public QuestionClipboardImageReader() {
	}

	/**
	 * Reads the current clipboard image and encodes it as PNG.
	 *
	 * @return encoded PNG bytes, or empty when the clipboard contains no image
	 * @throws IOException if the clipboard image cannot be encoded as PNG
	 */
	public Optional<byte[]> readPng() throws IOException {
		Clipboard clipboard = Clipboard.getSystemClipboard();
		if (!clipboard.hasImage()) {
			return Optional.empty();
		}
		Image image = clipboard.getImage();
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
