package au.edu.eq.questionbank.model;

import java.util.Arrays;

/**
 * Question content stored as an encoded PNG image.
 * <p>
 * Encoded bytes are retained instead of JavaFX or AWT image objects so the
 * domain model remains independent of presentation toolkits.
 */
public final class ImageQuestionContentPart implements QuestionContentPart {

	private final byte[] pngBytes;

	/**
	 * Creates an immutable image-content fragment.
	 *
	 * @param pngBytes non-empty encoded PNG data
	 * @throws NullPointerException     if {@code pngBytes} is {@code null}
	 * @throws IllegalArgumentException if the encoded image is empty
	 */
	public ImageQuestionContentPart(byte[] pngBytes) {
		if (pngBytes == null) {
			throw new NullPointerException("pngBytes");
		}
		if (pngBytes.length == 0) {
			throw new IllegalArgumentException("pngBytes must not be empty");
		}

		// Prevent later caller mutation from changing persisted Question content.
		this.pngBytes = pngBytes.clone();
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof ImageQuestionContentPart other)) {
			return false;
		}

		// Image identity is the immutable encoded content rather than array identity.
		return Arrays.equals(pngBytes, other.pngBytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(pngBytes);
	}

	/**
	 * Returns a defensive copy of the encoded PNG data.
	 *
	 * @return copied PNG bytes
	 */
	public byte[] pngBytes() {
		return pngBytes.clone();
	}

	@Override
	public String toString() {
		return "ImageQuestionContentPart[pngBytes=" + pngBytes.length + " bytes]";
	}
}
