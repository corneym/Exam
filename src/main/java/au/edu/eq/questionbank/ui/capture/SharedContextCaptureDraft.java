package au.edu.eq.questionbank.ui.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

/**
 * Mutable, session-local draft of ordered Shared Context content.
 *
 * PDF regions and PNG images share one authoritative sequence. The draft is not
 * persisted until the containing capture workflow saves it.
 */
final class SharedContextCaptureDraft {

	private final List<SharedContextContentPart> parts = new ArrayList<>();

	/**
	 * Accepts PNG content unless identical image bytes are already present.
	 *
	 * @param pngBytes encoded PNG bytes
	 * @return true if accepted; false when duplicate image content exists
	 */
	boolean addImage(byte[] pngBytes) {
		ImageSharedContextContentPart image = new ImageSharedContextContentPart(pngBytes);
		if (parts.contains(image)) {
			return false;
		}
		parts.add(image);
		return true;
	}

	/**
	 * Accepts a PDF region at the end of the content sequence.
	 *
	 * @param region non-null PDF region
	 */
	void addRegion(SharedQuestionContextRegion region) {
		parts.add(Objects.requireNonNull(region, "region"));
	}

	/**
	 * Discards every staged content part.
	 */
	void clear() {
		parts.clear();
	}

	/**
	 * Returns the authoritative ordered capture sequence.
	 *
	 * @return immutable snapshot
	 */
	List<SharedContextContentPart> contentParts() {
		return List.copyOf(parts);
	}

	/**
	 * Reports whether any content has been accepted.
	 *
	 * @return true when the draft is empty
	 */
	boolean isEmpty() {
		return parts.isEmpty();
	}

	/**
	 * Returns the PDF-only projection used by existing automatic capture.
	 *
	 * @return immutable PDF region sequence
	 */
	List<SharedQuestionContextRegion> regions() {
		List<SharedQuestionContextRegion> regions = new ArrayList<>();
		for (SharedContextContentPart part : parts) {
			if (part instanceof SharedQuestionContextRegion region) {
				regions.add(region);
			}
		}
		return List.copyOf(regions);
	}

	/**
	 * Removes one accepted content part by its mixed-content position.
	 *
	 * @param index zero-based position in the complete sequence
	 * @return removed content part
	 */
	SharedContextContentPart removeAt(int index) {
		return parts.remove(index);
	}

	/**
	 * Returns the number of accepted content parts.
	 *
	 * @return total PDF regions and images
	 */
	int size() {
		return parts.size();
	}
}
