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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

public class QuestionExtractor {

	private static final float RENDER_DPI = 150;

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

	public void extractQuestion(Path pdfPath, Question question, File outputFile) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
			PDFRenderer renderer = new PDFRenderer(document);

			List<BufferedImage> regionImages = new ArrayList<>();
			for (QuestionRegion region : question.getRegions()) {
				int pageIndex = region.pageNumber() - 1;
				BufferedImage page = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
				BufferedImage cropped = cropRegion(page, region);
				regionImages.add(cropped);
				int outputWidth = regionImages.stream().mapToInt(BufferedImage::getWidth).max().orElseThrow();
				int outputHeight = regionImages.stream().mapToInt(BufferedImage::getHeight).sum();
				BufferedImage combined = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = combined.createGraphics();
				graphics.setColor(Color.WHITE);
				graphics.fillRect(0, 0, outputWidth, outputHeight);
				int rectY = 0;
				for (BufferedImage image : regionImages) {
					graphics.drawImage(image, 0, rectY, null);
					rectY += image.getHeight();
				}
				graphics.dispose();
				ImageIO.write(combined, "png", outputFile);
			}
		}
	}

	public void extractRegion(Path pdfPath, QuestionRegion region, File outputFile) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
			PDFRenderer renderer = new PDFRenderer(document);

			// PDFBox page indexes are zero-indexed
			int pageIndex = region.pageNumber() - 1;
			BufferedImage page = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
			BufferedImage question = cropRegion(page, region);
			ImageIO.write(question, "png", outputFile);
		}
	}

	public void extractTestRegion(Path pdfPath, int pageIndex, File outputFile) throws IOException {
		try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
			PDFRenderer renderer = new PDFRenderer(document);
			BufferedImage page = renderer.renderImageWithDPI(pageIndex, 150);

			// TEMPORARY hard-coded crop
			int x = 100;
			int y = 400;
			int width = 1000;
			int height = 700;

			BufferedImage question = page.getSubimage(x, y, width, height);
			ImageIO.write(question, "png", outputFile);
		}
	}
}
