package au.edu.eq.questionbank.pdf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

/**
 * Renders source question regions to PNG images.
 * <p>
 * Rectangular regions are cropped from rendered pages and combined vertically
 * in list order. All regions passed to one extraction call are interpreted
 * against the supplied PDF path or session; booklet metadata is not used to
 * open additional files.
 */
public class QuestionExtractor {

	private static final float RENDER_DPI = 150;

	/**
	 * Opens a PDF, extracts all regions of a question, and writes a PNG image.
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
	 * Extracts and combines all regions of a question using an existing session.
	 * The session remains open.
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

	public BufferedImage extractRegion(PdfSession session, AnswerRegion region) throws IOException {

		BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);

		int left = (int) Math.floor(region.x() * page.getWidth());
		int top = (int) Math.floor(region.y() * page.getHeight());
		int right = (int) Math.ceil((region.x() + region.width()) * page.getWidth());
		int bottom = (int) Math.ceil((region.y() + region.height()) * page.getHeight());

		right = Math.min(right, page.getWidth());
		bottom = Math.min(bottom, page.getHeight());

		return page.getSubimage(left, top, right - left, bottom - top);
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

		int outputWidth = regionImages.stream().mapToInt(BufferedImage::getWidth).max().orElseThrow();
		int outputHeight = regionImages.stream().mapToInt(BufferedImage::getHeight).sum();
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

	private BufferedImage cropRegion(BufferedImage page, QuestionRegion region) {
		int left = (int) Math.floor(region.x() * page.getWidth());
		int top = (int) Math.floor(region.y() * page.getHeight());
		int right = (int) Math.ceil((region.x() + region.width()) * page.getWidth());
		int bottom = (int) Math.ceil((region.y() + region.height()) * page.getHeight());

		right = Math.min(right, page.getWidth());
		bottom = Math.min(bottom, page.getHeight());

		int width = right - left;
		int height = bottom - top;

		return page.getSubimage(left, top, width, height);
	}
}
