package au.edu.eq.questionbank.pdf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.imageio.ImageIO;

import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;

/**
 * Renders and crops source question or answer regions as raster images.
 * <p>
 * Question regions can be combined vertically in list order or written to PNG.
 * All regions passed to one extraction call are interpreted against the
 * supplied PDF path or session; source-document metadata is not used to open
 * additional files.
 */
public class QuestionExtractor {

	/**
	 * Creates an extractor for ordered question, answer and shared-context regions.
	 */
	public QuestionExtractor() {
	}

	private static final float RENDER_DPI = 150;

	/**
	 * Opens a PDF and writes the assembled question image to PNG. Linked
	 * shared-context regions are rendered first in their stored order, followed by
	 * the question's own regions in their stored order. Exact duplicate source
	 * rectangles are rendered only once.
	 *
	 * @param pdfPath    the PDF containing every question region in this call
	 * @param question   the question whose ordered regions are extracted
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractQuestion(Path pdfPath, Question question, File outputFile) throws Exception {

		// This overload owns the session and closes it even if rendering or PNG output
		// fails.
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractQuestion(session, question);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Extracts the assembled question image using an existing session. Linked
	 * shared-context regions are rendered first in their stored order, followed by
	 * the question's own regions in their stored order. Exact duplicate source
	 * rectangles are rendered only once. The session remains open.
	 *
	 * @param session  the PDF session containing the question and context regions
	 * @param question the question to extract
	 * @return the vertically combined image
	 * @throws IOException            if a page cannot be rendered
	 * @throws NoSuchElementException if the question has no question regions
	 */
	public BufferedImage extractQuestion(PdfSession session, Question question) throws IOException {
		if (question.getRegions().isEmpty()) {
			throw new NoSuchElementException("No value present");
		}
		List<BufferedImage> regionImages = new ArrayList<>();
		Set<RegionKey> renderedRegions = new HashSet<>();

		// Render context first; the shared key set also removes exact duplicates from
		// the later question body.
		if (question.hasSharedContext()) {
			for (SharedQuestionContextRegion region : question.getSharedContext().getRegions()) {
				RegionKey key = RegionKey.from(region);
				if (renderedRegions.add(key)) {
					regionImages.add(extractRegion(session, region));
				}
			}
		}
		for (QuestionRegion region : question.getRegions()) {
			RegionKey key = RegionKey.from(region);
			if (renderedRegions.add(key)) {
				regionImages.add(extractRegion(session, region));
			}
		}
		return combineRegionImages(regionImages);
	}

	/**
	 * Opens a PDF and writes only the question's own ordered regions to PNG. Linked
	 * shared context is deliberately excluded.
	 *
	 * @param pdfPath    the PDF containing the question regions
	 * @param question   the question whose own regions are extracted
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractQuestionBody(Path pdfPath, Question question, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractQuestionBody(session, question);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Extracts only the question's own ordered regions using an existing session.
	 * Linked shared context is deliberately excluded. Exact duplicate source
	 * rectangles are rendered only once. The session remains open.
	 *
	 * @param session  the PDF session containing the question regions
	 * @param question the question to extract
	 * @return the vertically combined question-body image
	 * @throws IOException            if a page cannot be rendered
	 * @throws NoSuchElementException if the question has no question regions
	 */
	public BufferedImage extractQuestionBody(PdfSession session, Question question) throws IOException {
		if (question.getRegions().isEmpty()) {
			throw new NoSuchElementException("No value present");
		}
		List<BufferedImage> regionImages = new ArrayList<>();

		// Keep first-occurrence order in the image list; the set only tracks rectangles
		// already rendered.
		Set<RegionKey> renderedRegions = new HashSet<>();
		for (QuestionRegion region : question.getRegions()) {
			RegionKey key = RegionKey.from(region);
			if (renderedRegions.add(key)) {
				regionImages.add(extractRegion(session, region));
			}
		}
		return combineRegionImages(regionImages);
	}

	/**
	 * Opens a PDF, extracts one answer region, and writes a PNG image.
	 *
	 * @param pdfPath    the PDF represented by the answer region's source document
	 * @param region     the answer region to extract
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractRegion(Path pdfPath, AnswerRegion region, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractRegion(session, region);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Opens a PDF, extracts one rectangular region, and writes a PNG image.
	 *
	 * @param pdfPath    the PDF represented by the region's booklet source document
	 * @param region     the region to extract
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractRegion(Path pdfPath, QuestionRegion region, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractRegion(session, region);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Extracts one answer region using an existing PDF session. The session remains
	 * open.
	 *
	 * @param session the session for the region's answer-file source document
	 * @param region  the rectangular answer region to extract
	 * @return the cropped region image
	 * @throws IOException if the page cannot be rendered
	 */
	public BufferedImage extractRegion(PdfSession session, AnswerRegion region) throws IOException {
		BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);
		return cropRegion(page, region.x(), region.y(), region.width(), region.height());
	}

	/**
	 * Extracts one region using an existing PDF session. The session remains open.
	 *
	 * @param session the session for the region's booklet source document
	 * @param region  the rectangular region to extract
	 * @return the cropped region image
	 * @throws IOException if the page cannot be rendered
	 */
	public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
		BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);
		return cropRegion(page, region);
	}

	/**
	 * Extracts one shared-question-context region using an existing PDF session.
	 * The session remains open.
	 *
	 * @param session the exam PDF session
	 * @param region  the shared-context source region
	 * @return the cropped region image
	 * @throws IOException if the page cannot be rendered
	 */
	public BufferedImage extractRegion(PdfSession session, SharedQuestionContextRegion region) throws IOException {
		BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);
		return cropRegion(page, region.x(), region.y(), region.width(), region.height());
	}

	/**
	 * Opens a PDF and writes one complete shared context to PNG.
	 *
	 * @param pdfPath    the PDF containing the shared-context regions
	 * @param context    the shared context to extract
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractSharedContext(Path pdfPath, SharedQuestionContext context, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractSharedContext(session, context);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Extracts all ordered regions belonging to one shared context using an
	 * existing session. Exact duplicate source rectangles are rendered only once.
	 * The session remains open.
	 *
	 * @param session the PDF session containing the shared-context regions
	 * @param context the shared context to extract
	 * @return the vertically combined shared-context image
	 * @throws IOException if a page cannot be rendered
	 */
	public BufferedImage extractSharedContext(PdfSession session, SharedQuestionContext context) throws IOException {
		List<BufferedImage> regionImages = new ArrayList<>();

		// Deduplicate within this context while retaining its stored reading order.
		Set<RegionKey> renderedRegions = new HashSet<>();
		for (SharedQuestionContextRegion region : context.getRegions()) {
			RegionKey key = RegionKey.from(region);
			if (renderedRegions.add(key)) {
				regionImages.add(extractRegion(session, region));
			}
		}
		return combineRegionImages(regionImages);
	}

	private BufferedImage combineRegionImages(List<BufferedImage> regionImages) {
		if (regionImages.isEmpty()) {
			throw new NoSuchElementException("No value present");
		}

		// Stack at original scale: use the widest region and the sum of all region
		// heights.
		int outputWidth = 0;
		int outputHeight = 0;
		for (BufferedImage image : regionImages) {
			outputWidth = Math.max(outputWidth, image.getWidth());
			outputHeight += image.getHeight();
		}
		BufferedImage combined = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = combined.createGraphics();
		try {

			// White fills the unused space beside narrower, left-aligned regions.
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, outputWidth, outputHeight);
			int y = 0;
			for (BufferedImage image : regionImages) {
				graphics.drawImage(image, 0, y, null);
				y += image.getHeight();
			}
		} finally {
			graphics.dispose();
		}
		return combined;
	}

	private BufferedImage cropRegion(BufferedImage page, double x, double y, double width, double height) {

		// Round the displayed-page endpoints outwards so partially covered pixels are
		// retained.
		int left = (int) Math.floor(x * page.getWidth());
		int top = (int) Math.floor(y * page.getHeight());
		int right = (int) Math.ceil((x + width) * page.getWidth());
		int bottom = (int) Math.ceil((y + height) * page.getHeight());

		// Clamp far edges to the raster, then derive dimensions from endpoints rather
		// than rounding sizes.
		right = Math.min(right, page.getWidth());
		bottom = Math.min(bottom, page.getHeight());
		int cropWidth = right - left;
		int cropHeight = bottom - top;
		return page.getSubimage(left, top, cropWidth, cropHeight);
	}

	private BufferedImage cropRegion(BufferedImage page, QuestionRegion region) {
		return cropRegion(page, region.x(), region.y(), region.width(), region.height());
	}

	// All regions in one extraction use the same PDF; exact page and bounds
	// identify duplicates.
	private record RegionKey(int pageNumber, double x, double y, double width, double height) {

		private static RegionKey from(QuestionRegion region) {
			return new RegionKey(region.pageNumber(), region.x(), region.y(), region.width(), region.height());
		}

		private static RegionKey from(SharedQuestionContextRegion region) {
			return new RegionKey(region.pageNumber(), region.x(), region.y(), region.width(), region.height());
		}
	}
}
