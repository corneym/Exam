package au.edu.eq.questionbank.pdf;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

/**
 * Reproduces selected PDF regions at their original physical scale and replaces
 * printed question part identifiers when requested.
 *
 * <p>
 * The source PDF is not modified. Clipping and label replacement affect the
 * visible output rather than removing underlying PDF text.
 */
public final class PrintablePdfRegionWriter {

	private PrintablePdfRegionWriter() {
	}

	/**
	 * Appends a selected source region to a destination PDF page.
	 *
	 * <p>
	 * Coordinates are expressed in PDF points measured from the bottom-left corner.
	 * This operation supports ordinary unrotated source pages with a zero-origin
	 * crop box.
	 *
	 * @param source           the source PDF document
	 * @param pageNumber       the one-based source page number
	 * @param output           the destination PDF document
	 * @param stream           the destination page content stream
	 * @param region           the selected source rectangle
	 * @param destinationX     the destination left edge
	 * @param destinationY     the destination bottom edge
	 * @param originalLabel    the original printed label, or an empty string when
	 *                         no replacement is required
	 * @param replacementLabel the replacement label, or an empty string to remove
	 *                         the original label
	 * @throws IOException if PDF processing fails, the source page geometry is
	 *                     unsupported, or a requested original label cannot be
	 *                     located
	 */
	public static void appendRegion(PDDocument source, int pageNumber, PDDocument output, PDPageContentStream stream,
			PDRectangle region, float destinationX, float destinationY, String originalLabel, String replacementLabel)
			throws IOException {
		PDPage sourcePage = source.getPage(pageNumber - 1);
		PDRectangle cropBox = sourcePage.getCropBox();
		if (sourcePage.getRotation() != 0 || cropBox.getLowerLeftX() != 0 || cropBox.getLowerLeftY() != 0) {
			throw new IOException("Unsupported source page geometry.");
		}
		List<TextPosition> labelPositions = List.of();
		if (!originalLabel.isEmpty()) {
			labelPositions = locateLabel(source, pageNumber, region, originalLabel);
		}
		LayerUtility layers = new LayerUtility(output);
		PDFormXObject form = layers.importPageAsForm(source, pageNumber - 1);
		float translateX = destinationX - region.getLowerLeftX();
		float translateY = destinationY - region.getLowerLeftY();

		// Restrict visible content to the captured rectangle,
		// retaining the original physical scale.
		stream.saveGraphicsState();
		stream.addRect(destinationX, destinationY, region.getWidth(), region.getHeight());
		stream.clip();
		stream.transform(Matrix.getTranslateInstance(translateX, translateY));
		stream.drawForm(form);
		stream.restoreGraphicsState();
		if (!labelPositions.isEmpty()) {
			replaceLabel(stream, labelPositions, cropBox.getHeight(), translateX, translateY, replacementLabel);
		}
	}

	private static List<TextPosition> locateLabel(PDDocument source, int pageNumber, PDRectangle region,
			String originalLabel) throws IOException {
		List<TextPosition> positions = new ArrayList<>();
		float pageHeight = source.getPage(pageNumber - 1).getCropBox().getHeight();
		PDFTextStripper stripper = new PDFTextStripper() {

			@Override
			protected void processTextPosition(TextPosition text) {
				float x = text.getXDirAdj();
				float y = pageHeight - text.getYDirAdj();

				// Only consider characters within the captured region.
				if (x >= region.getLowerLeftX() && x <= region.getUpperRightX() && y >= region.getLowerLeftY()
						&& y <= region.getUpperRightY()) {
					positions.add(text);
				}
				super.processTextPosition(text);
			}
		};
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		stripper.getText(source);
		List<TextPosition> bestMatch = List.of();
		float bestY = Float.MAX_VALUE;
		float bestX = Float.MAX_VALUE;

		// Locate the requested sequence of characters on one baseline.
		// Prefer the uppermost, then leftmost, occurrence.
		for (int start = 0; start < positions.size(); start++) {
			TextPosition first = positions.get(start);
			float baseline = first.getYDirAdj();
			StringBuilder candidate = new StringBuilder();
			List<TextPosition> matchedPositions = new ArrayList<>();
			for (int index = start; index < positions.size(); index++) {
				TextPosition position = positions.get(index);
				if (Math.abs(position.getYDirAdj() - baseline) > 2) {
					break;
				}
				candidate.append(position.getUnicode());
				if (!originalLabel.startsWith(candidate.toString())) {
					break;
				}
				matchedPositions.add(position);
				if (originalLabel.contentEquals(candidate)) {
					float x = first.getXDirAdj();
					if (baseline < bestY || (baseline == bestY && x < bestX)) {
						bestMatch = List.copyOf(matchedPositions);
						bestY = baseline;
						bestX = x;
					}
					break;
				}
			}
		}
		if (bestMatch.isEmpty()) {
			throw new IOException("Cannot locate part label: " + originalLabel);
		}
		return bestMatch;
	}

	private static void replaceLabel(PDPageContentStream stream, List<TextPosition> positions, float pageHeight,
			float translateX, float translateY, String replacementLabel) throws IOException {
		TextPosition first = positions.getFirst();
		TextPosition last = positions.getLast();
		float x = first.getXDirAdj() + translateX;
		float baselineY = pageHeight - first.getYDirAdj() + translateY;
		float width = last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj();
		float fontSize = first.getFontSizeInPt();

		// Conceal only the original label, including a small margin
		// around its rendered characters.
		stream.saveGraphicsState();
		stream.setNonStrokingColor(Color.WHITE);
		stream.addRect(x - 2, baselineY - 5, width + 4, fontSize + 8);
		stream.fill();
		stream.restoreGraphicsState();

		// An empty replacement removes the visible part identifier.
		if (replacementLabel.isEmpty()) {
			return;
		}
		stream.saveGraphicsState();
		stream.setNonStrokingColor(Color.BLACK);
		stream.beginText();
		stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
		stream.newLineAtOffset(x, baselineY);
		stream.showText(replacementLabel);
		stream.endText();
		stream.restoreGraphicsState();
	}
}
