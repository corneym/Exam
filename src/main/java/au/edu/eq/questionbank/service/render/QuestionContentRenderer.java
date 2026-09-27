package au.edu.eq.questionbank.service.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.imageio.ImageIO;

import au.edu.eq.questionbank.model.ImageQuestionContentPart;
import au.edu.eq.questionbank.model.PdfQuestionContentPart;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionContentPart;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.pdf.PdfSession;
import au.edu.eq.questionbank.pdf.PdfStore;
import au.edu.eq.questionbank.pdf.QuestionExtractor;

/**
 * Renders a Question body from its ordered mixed source content.
 * <p>
 * PDF regions are delegated to {@link QuestionExtractor}. Stored image content
 * is decoded directly from its persisted PNG representation. The resulting
 * fragments are assembled vertically in the authoritative order supplied by
 * {@link Question#getContentParts()}.
 * <p>
 * Linked shared context can optionally be prepended for interactive previews.
 * Revision output renders shared context separately and therefore uses
 * {@link #renderQuestionBody(Question)}.
 */
public final class QuestionContentRenderer {

	private final PdfStore pdfStore;
	private final QuestionExtractor questionExtractor;

	/**
	 * Creates a mixed Question-content renderer.
	 *
	 * @param pdfStore          resolver for managed source PDFs
	 * @param questionExtractor PDF-region renderer
	 * @throws NullPointerException if either dependency is {@code null}
	 */
	public QuestionContentRenderer(PdfStore pdfStore, QuestionExtractor questionExtractor) {
		if (pdfStore == null) {
			throw new NullPointerException("pdfStore");
		}
		if (questionExtractor == null) {
			throw new NullPointerException("questionExtractor");
		}
		this.pdfStore = pdfStore;
		this.questionExtractor = questionExtractor;
	}

	/**
	 * Renders only the Question's own mixed body content.
	 * <p>
	 * Shared context is deliberately excluded.
	 *
	 * @param question Question to render
	 * @return vertically assembled Question-body image
	 * @throws IOException            if required source data cannot be read
	 * @throws NoSuchElementException if the Question has no body content
	 */
	public BufferedImage renderQuestionBody(Question question) throws IOException {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.getContentParts().isEmpty()) {
			throw new NoSuchElementException("Question has no content");
		}

		// An image-only Question must not require its source PDF to remain available.
		if (!requiresPdf(question.getContentParts())) {
			return combineImages(renderBodyParts(null, question.getContentParts(), new HashSet<>()));
		}
		Path sourcePdf = requireSourcePdf(question);
		try (PdfSession session = PdfSession.open(sourcePdf)) {

			// Keep PDF lifecycle failures inside the renderer's IOException boundary.
			return combineImages(renderBodyParts(session, question.getContentParts(), new HashSet<>()));
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not close PDF session for question " + question.getId(), e);
		}
	}

	/**
	 * Renders a preview containing linked shared context followed by the Question's
	 * own mixed content.
	 *
	 * @param question Question to preview
	 * @return complete preview image
	 * @throws IOException            if required source data cannot be read
	 * @throws NoSuchElementException if the Question has no body content
	 */
	public BufferedImage renderQuestionPreview(Question question) throws IOException {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (question.getContentParts().isEmpty()) {
			throw new NoSuchElementException("Question has no content");
		}
		boolean requiresPdf = question.hasSharedContext() || requiresPdf(question.getContentParts());
		if (!requiresPdf) {
			return combineImages(renderBodyParts(null, question.getContentParts(), new HashSet<>()));
		}
		Path sourcePdf = requireSourcePdf(question);
		try (PdfSession session = PdfSession.open(sourcePdf)) {
			List<BufferedImage> images = new ArrayList<>();
			Set<RegionKey> renderedPdfRegions = new HashSet<>();

			// Interactive preview retains the established behaviour of displaying linked
			// shared context before the Question body.
			if (question.hasSharedContext()) {
				for (SharedQuestionContextRegion region : question.getSharedContext().getRegions()) {
					RegionKey key = RegionKey.from(region);
					if (renderedPdfRegions.add(key)) {
						images.add(questionExtractor.extractRegion(session, region));
					}
				}
			}

			// Use the same region-key set for the body so an exact region already shown
			// as shared context is not rendered twice.
			images.addAll(renderBodyParts(session, question.getContentParts(), renderedPdfRegions));
			return combineImages(images);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not close PDF session for question " + question.getId(), e);
		}
	}

	/**
	 * Renders the Question body and writes it as PNG.
	 *
	 * @param question   Question to render
	 * @param outputFile destination PNG path
	 * @throws IOException if rendering or writing fails
	 */
	public void writeQuestionBody(Question question, Path outputFile) throws IOException {
		if (outputFile == null) {
			throw new NullPointerException("outputFile");
		}
		BufferedImage image = renderQuestionBody(question);

		// ImageIO reports unsupported output formats by returning false rather than
		// throwing, so treat that as a failed asset write.
		if (!ImageIO.write(image, "png", outputFile.toFile())) {
			throw new IOException("No PNG writer is available");
		}
	}

	private BufferedImage combineImages(List<BufferedImage> images) {
		if (images.isEmpty()) {
			throw new NoSuchElementException("Question has no renderable content");
		}
		int outputWidth = 0;
		int outputHeight = 0;
		for (BufferedImage image : images) {
			outputWidth = Math.max(outputWidth, image.getWidth());
			outputHeight += image.getHeight();
		}
		BufferedImage combined = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = combined.createGraphics();
		try {

			// Narrower content parts are left aligned against a white background,
			// matching the existing QuestionExtractor composition behaviour.
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, outputWidth, outputHeight);
			int y = 0;
			for (BufferedImage image : images) {
				graphics.drawImage(image, 0, y, null);
				y += image.getHeight();
			}
		} finally {
			graphics.dispose();
		}
		return combined;
	}

	private BufferedImage decodeImage(ImageQuestionContentPart imagePart) throws IOException {
		try (ByteArrayInputStream input = new ByteArrayInputStream(imagePart.pngBytes())) {
			BufferedImage image = ImageIO.read(input);
			if (image == null) {
				throw new IOException("Stored Question image is not a readable PNG");
			}
			return image;
		}
	}

	private List<BufferedImage> renderBodyParts(PdfSession session, List<QuestionContentPart> contentParts,
			Set<RegionKey> renderedPdfRegions) throws IOException {
		List<BufferedImage> images = new ArrayList<>();
		for (QuestionContentPart part : contentParts) {
			if (part instanceof ImageQuestionContentPart imagePart) {

				// Image content participates directly in assembly order and is never
				// deduplicated by PDF coordinates.
				images.add(decodeImage(imagePart));
				continue;
			}
			if (part instanceof PdfQuestionContentPart pdfPart) {
				if (session == null) {
					throw new IllegalStateException("PDF session required for PDF Question content");
				}
				QuestionRegion region = pdfPart.region();
				RegionKey key = RegionKey.from(region);

				// Preserve the existing first-occurrence behaviour for duplicate source
				// rectangles.
				if (renderedPdfRegions.add(key)) {
					images.add(questionExtractor.extractRegion(session, region));
				}
				continue;
			}
			throw new IllegalStateException("Unsupported Question content part: " + part.getClass().getName());
		}
		return images;
	}

	private Path requireSourcePdf(Question question) throws IOException {
		String relativePath = question.getBooklet().getSourceDocument().getRelativePath();
		Path sourcePdf = pdfStore.resolve(relativePath);
		if (!Files.isRegularFile(sourcePdf)) {
			throw new IOException(
					"Question source PDF is not available for question " + question.getId() + ": " + sourcePdf);
		}
		return sourcePdf;
	}

	private boolean requiresPdf(List<QuestionContentPart> contentParts) {
		for (QuestionContentPart part : contentParts) {
			if (part instanceof PdfQuestionContentPart) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Identifies one exact PDF source rectangle independently of Java object
	 * identity.
	 */
	private record RegionKey(int pageNumber, double x, double y, double width, double height) {

		private static RegionKey from(QuestionRegion region) {
			return new RegionKey(region.pageNumber(), region.x(), region.y(), region.width(), region.height());
		}

		private static RegionKey from(SharedQuestionContextRegion region) {
			return new RegionKey(region.pageNumber(), region.x(), region.y(), region.width(), region.height());
		}
	}
}
