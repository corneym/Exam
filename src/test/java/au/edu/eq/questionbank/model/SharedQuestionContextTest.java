package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedQuestionContextTest {

	private ExamBooklet booklet;

	@Test
	void imageOnlyContextRetainsImmutablePngContent() {
		byte[] supplied = { 10, 20, 30, 40 };
		ImageSharedContextContentPart image = new ImageSharedContextContentPart(supplied);
		SharedQuestionContext context = SharedQuestionContext.fromContentParts(8, booklet, "Image-only context",
				List.of(image));
		supplied[0] = 99;
		assertEquals(1, context.getContentParts().size());
		assertEquals(List.of(), context.getRegions());
		ImageSharedContextContentPart stored = (ImageSharedContextContentPart) context.getContentParts().getFirst();
		assertArrayEquals(new byte[] { 10, 20, 30, 40 }, stored.pngBytes());
		byte[] returned = stored.pngBytes();
		returned[1] = 99;
		assertArrayEquals(new byte[] { 10, 20, 30, 40 }, stored.pngBytes());
		assertThrows(UnsupportedOperationException.class, () -> context.getContentParts().clear());
	}

	@Test
	void mixedContextPreservesCompleteOrderAndPdfProjection() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(1, 0.1, 0.1, 0.6, 0.2);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(2, 0.2, 0.2, 0.5, 0.3);
		ImageSharedContextContentPart imageA = new ImageSharedContextContentPart(new byte[] { 1, 2, 3 });
		ImageSharedContextContentPart imageB = new ImageSharedContextContentPart(new byte[] { 4, 5, 6 });
		List<SharedContextContentPart> supplied = new ArrayList<>(List.of(first, imageA, second, imageB));
		SharedQuestionContext context = SharedQuestionContext.fromContentParts(9, booklet, "Mixed context", supplied);
		supplied.clear();
		assertEquals(List.of(first, imageA, second, imageB), context.getContentParts());
		assertEquals(List.of(first, second), context.getRegions());
		assertThrows(UnsupportedOperationException.class, () -> context.getRegions().clear());
	}

	@Test
	void mixedContextRejectsInvalidContent() {
		ImageSharedContextContentPart image = new ImageSharedContextContentPart(new byte[] { 1, 2, 3 });
		List<SharedContextContentPart> withNull = new ArrayList<>();
		withNull.add(image);
		withNull.add(null);
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> SharedQuestionContext.fromContentParts(0, booklet, "Context", List.of(image))),
				() -> assertThrows(NullPointerException.class,
						() -> SharedQuestionContext.fromContentParts(1, null, "Context", List.of(image))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> SharedQuestionContext.fromContentParts(1, booklet, " ", List.of(image))),
				() -> assertThrows(NullPointerException.class,
						() -> SharedQuestionContext.fromContentParts(1, booklet, "Context", null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> SharedQuestionContext.fromContentParts(1, booklet, "Context", List.of())),
				() -> assertThrows(NullPointerException.class,
						() -> SharedQuestionContext.fromContentParts(1, booklet, "Context", withNull)),
				() -> assertThrows(NullPointerException.class, () -> new ImageSharedContextContentPart(null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new ImageSharedContextContentPart(new byte[0])));
	}

	@Test
	void pdfOnlyConstructorPopulatesMixedContentProjection() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(1, 0.1, 0.1, 0.5, 0.2);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(2, 0.2, 0.3, 0.4, 0.2);
		SharedQuestionContext context = new SharedQuestionContext(10, booklet, "Existing PDF context",
				List.of(first, second));
		assertEquals(List.of(first, second), context.getRegions());
		assertEquals(List.of(first, second), context.getContentParts());
		assertTrue(context.getContentParts().stream().allMatch(part -> part instanceof SharedQuestionContextRegion));
	}

	@Test
	void rejectsInvalidMetadataAndRegions() {
		List<SharedQuestionContextRegion> nullRegionList = new ArrayList<>();
		nullRegionList.add(null);
		assertAll(
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(0, booklet, "Shared Context",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(NullPointerException.class,
						() -> new SharedQuestionContext(1, null, "Shared Context",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(1, booklet, " ",
								List.of(new SharedQuestionContextRegion(1, 0, 0, 1, 1)))),
				() -> assertThrows(NullPointerException.class,
						() -> new SharedQuestionContext(1, booklet, "Shared Context", null)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> new SharedQuestionContext(1, booklet, "Shared Context", List.of())),
				() -> assertThrows(NullPointerException.class,
						() -> new SharedQuestionContext(1, booklet, "Shared Context", nullRegionList)));
	}

	@Test
	void retainsOrderedRegions() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(3, 0.1, 0.1, 0.8, 0.2);
		SharedQuestionContextRegion second = new SharedQuestionContextRegion(4, 0.1, 0.2, 0.8, 0.3);
		SharedQuestionContext context = new SharedQuestionContext(7, booklet, "Question 21 shared context",
				List.of(first, second));
		assertAll(() -> assertEquals(7, context.getId()), () -> assertSame(booklet, context.getBooklet()),
				() -> assertEquals("Question 21 shared context", context.getLabel()),
				() -> assertEquals(List.of(first, second), context.getRegions()));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2025, "External assessment");
		booklet = new ExamBooklet(1, exam, "Paper 1", new SourceDocument(1, "paper-1.pdf"));
	}

	@Test
	void takesImmutableSnapshotOfRegions() {
		SharedQuestionContextRegion first = new SharedQuestionContextRegion(3, 0, 0, 1, 0.2);
		List<SharedQuestionContextRegion> supplied = new ArrayList<>();
		supplied.add(first);
		SharedQuestionContext context = new SharedQuestionContext(7, booklet, "Shared Context", supplied);
		supplied.add(new SharedQuestionContextRegion(4, 0, 0, 1, 0.2));
		assertEquals(List.of(first), context.getRegions());
	}
}
