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

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

public class QuestionExtractor {

	private static final float RENDER_DPI = 150;

	public void extractQuestion(Path pdfPath, Question question, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractQuestion(session, question);
			ImageIO.write(image, "png", outputFile);
		}
	}

	public BufferedImage extractQuestion(PdfSession session, Question question) throws IOException {
		List<BufferedImage> regionImages = new ArrayList<>();
		for (QuestionRegion region : question.getRegions()) {
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

	public void extractRegion(Path pdfPath, QuestionRegion region, File outputFile) throws Exception {
		try (PdfSession session = PdfSession.open(pdfPath)) {
			BufferedImage image = extractRegion(session, region);
			ImageIO.write(image, "png", outputFile);
		}
	}

	public BufferedImage extractRegion(PdfSession session, QuestionRegion region) throws IOException {
		BufferedImage page = session.renderPage(region.pageNumber(), RENDER_DPI);
		return cropRegion(page, region);
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
