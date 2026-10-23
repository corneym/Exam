package au.edu.eq.questionbank.model;

/**
 * Immutable PNG image content belonging to a reusable Shared Context.
 * <p>
 * The encoded image is retained independently of JavaFX and AWT.
 */
public final class ImageSharedContextContentPart implements SharedContextContentPart {

	private final ImageQuestionContentPart image;

	/**
	 * Creates an immutable Shared Context image.
	 *
	 * @param pngBytes non-empty PNG data, copied defensively
	 * @throws NullPointerException     if the byte array is null
	 * @throws IllegalArgumentException if the byte array is empty
	 */
	public ImageSharedContextContentPart(byte[] pngBytes) {
		this.image = new ImageQuestionContentPart(pngBytes);
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof ImageSharedContextContentPart other)) {
			return false;
		}
		return image.equals(other.image);
	}

	@Override
	public int hashCode() {
		return image.hashCode();
	}

	/**
	 * Returns a defensive copy of the encoded PNG bytes.
	 *
	 * @return copied PNG data
	 */
	public byte[] pngBytes() {
		return image.pngBytes();
	}

	@Override
	public String toString() {
		return "ImageSharedContextContentPart[image=" + image + "]";
	}
}
