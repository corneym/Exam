package au.edu.eq.questionbank.pdf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import javax.imageio.ImageIO;

import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
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

	private static final float RENDER_DPI = 150;

	private BufferedImage cropRegion(BufferedImage page, QuestionRegion region) {
		return cropRegion(page, region.x(), region.y(), region.width(), region.height());
	}

	private BufferedImage cropRegion(BufferedImage page, double x, double y, double width, double height) {
		int left = (int) Math.floor(x * page.getWidth());
		int top = (int) Math.floor(y * page.getHeight());
		int right = (int) Math.ceil((x + width) * page.getWidth());
		int bottom = (int) Math.ceil((y + height) * page.getHeight());
		right = Math.min(right, page.getWidth());
		bottom = Math.min(bottom, page.getHeight());
		int cropWidth = right - left;
		int cropHeight = bottom - top;
		return page.getSubimage(left, top, cropWidth, cropHeight);
	}

	/**
	 * Opens a PDF, extracts the question's own ordered regions, and writes a PNG.
	 * Linked shared-context regions are not included automatically.
	 *
	 * @param pdfPath    the PDF containing every question region in this call
	 * @param question   the question whose ordered regions are extracted
	 * @param outputFile the destination PNG file
	 * @throws Exception if the PDF cannot be opened, rendered, closed, or written
	 */
	public void extractQuestion(Path pdfPath, Question question, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractQuestion(session, question);
			ImageIO.write(image, "png", outputFile);
		}
	}

	/**
	 * Extracts and combines the question's own regions using an existing session.
	 * Linked shared-context regions are not included. The session remains open.
	 *
	 * @param session  the PDF session containing every question region in this call
	 * @param question the question to extract
	 * @return the vertically combined image
	 * @throws IOException if a page cannot be rendered
	 */
	public BufferedImage extractQuestion(PdfSession session, Question question) throws IOException {
		return extractRegions(session, question.getRegions());
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
	 * Extracts non-empty ordered regions from one PDF and stacks them vertically.
	 * Narrower rendered pages are left-aligned and padded with white to the widest
	 * region.
	 *
	 * @param session the PDF session containing every region in this call
	 * @param regions non-empty regions in output order
	 * @return one combined RGB image
	 * @throws IOException                      if a page cannot be rendered
	 * @throws java.util.NoSuchElementException if {@code regions} is empty
	 */
	public BufferedImage extractRegions(PdfSession session, List<QuestionRegion> regions) throws IOException {
		List<BufferedImage> regionImages = new ArrayList<>();
		for (QuestionRegion region : regions) {
			BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);
			BufferedImage cropped = cropRegion(page, region);
			regionImages.add(cropped);
		}
		if (regionImages.isEmpty()) {
			throw new NoSuchElementException("No value present");
		}
		int outputWidth = 0;
		int outputHeight = 0;
		for (BufferedImage image : regionImages) {
			outputWidth = Math.max(outputWidth, image.getWidth());
			outputHeight += image.getHeight();
		}
		BufferedImage combined = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = combined.createGraphics();
		try {
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
}
