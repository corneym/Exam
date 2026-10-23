package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.ImageSharedContextContentPart;
import au.edu.eq.questionbank.model.SharedContextContentPart;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

class SharedContextCaptureDraftTest {

	@Test
	void acceptsImageOnlyContext() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		byte[] png = { 1, 2, 3, 4 };
		assertTrue(draft.addImage(png));
		assertEquals(1, draft.size());
		assertTrue(draft.regions().isEmpty());
		ImageSharedContextContentPart saved = (ImageSharedContextContentPart) draft.contentParts().getFirst();
		assertEquals(new ImageSharedContextContentPart(png), saved);
	}

	@Test
	void clearingDraftDiscardsEveryContentType() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		draft.addRegion(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20));
		draft.addImage(new byte[] { 1, 3, 5 });
		assertEquals(2, draft.size());
		draft.clear();
		assertTrue(draft.isEmpty());
		assertTrue(draft.contentParts().isEmpty());
		assertTrue(draft.regions().isEmpty());
	}

	@Test
	void permitsSameImageAfterRemoval() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		byte[] png = { 11, 22, 33 };
		assertTrue(draft.addImage(png));
		draft.removeAt(0);
		assertTrue(draft.isEmpty());
		assertTrue(draft.addImage(png));
		assertEquals(1, draft.size());
	}

	@Test
	void permitsSameImageInDifferentContexts() {
		SharedContextCaptureDraft first = new SharedContextCaptureDraft();
		SharedContextCaptureDraft second = new SharedContextCaptureDraft();
		byte[] png = { 40, 50, 60 };
		assertTrue(first.addImage(png));
		assertTrue(second.addImage(png));
		assertEquals(first.contentParts(), second.contentParts());
	}

	@Test
	void preservesInterleavedPdfAndImageOrder() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(1, 0.10, 0.10, 0.50, 0.20);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(2, 0.15, 0.20, 0.60, 0.30);
		ImageSharedContextContentPart image = new ImageSharedContextContentPart(new byte[] { 5, 6, 7 });
		draft.addRegion(first);
		assertTrue(draft.addImage(new byte[] { 5, 6, 7 }));
		draft.addRegion(second);
		assertEquals(List.of(first, image, second), draft.contentParts());

		// Existing PDF-only consumers retain relative region order.
		assertEquals(List.of(first, second), draft.regions());
	}

	@Test
	void rejectsDuplicateImageWithoutChangingOrder() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		byte[] png = { 10, 20, 30 };
		assertTrue(draft.addImage(png));
		List<SharedContextContentPart> before = draft.contentParts();
		assertFalse(draft.addImage(png));
		assertEquals(before, draft.contentParts());
		assertEquals(1, draft.size());
	}

	@Test
	void rejectsNullRegionsAndInvalidImages() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		assertThrows(NullPointerException.class, () -> draft.addRegion(null));
		assertThrows(NullPointerException.class, () -> draft.addImage(null));
		assertThrows(IllegalArgumentException.class, () -> draft.addImage(new byte[0]));
		assertTrue(draft.isEmpty());
	}

	@Test
	void snapshotsCannotBeModifiedOrChangeWithDraft() {
		SharedContextCaptureDraft draft = new SharedContextCaptureDraft();
		draft.addImage(new byte[] { 7, 8, 9 });
		List<SharedContextContentPart> snapshot = draft.contentParts();
		assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
		draft.clear();
		assertEquals(1, snapshot.size());
		assertTrue(draft.isEmpty());
		assertNotSame(snapshot, draft.contentParts());
	}
}
