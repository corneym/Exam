package au.edu.eq.questionbank.pdf;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
	 * Coordinates are expressed in PDF points measured from the bottom-left corner
	 * of the source crop box. Both zero-origin and offset crop boxes are supported
	 * for unrotated source pages.
	 *
	 * @param source           the source PDF document
	 * @param pageNumber       the one-based source page number
	 * @param output           the destination PDF document
	 * @param stream           the destination page content stream
	 * @param region           the selected crop-relative source rectangle
	 * @param destinationX     the destination left edge
	 * @param destinationY     the destination bottom edge
	 * @param originalLabel    the original printed label, or an empty string when
	 *                         no replacement is required
	 * @param replacementLabel the replacement label, or an empty string to remove
	 *                         the original label
	 * @throws IOException if PDF processing fails, the source page rotation is
	 *                     unsupported, or a requested original label cannot be
	 *                     located
	 */
	public static void appendRegion(PDDocument source, int pageNumber, PDDocument output, PDPageContentStream stream,
			PDRectangle region, float destinationX, float destinationY, String originalLabel, String replacementLabel)
			throws IOException {
		PDPage sourcePage = source.getPage(pageNumber - 1);
		PDRectangle cropBox = sourcePage.getCropBox();
		if (sourcePage.getRotation() != 0) {
			throw new IOException("Unsupported source page geometry: rotation " + sourcePage.getRotation());
		}
		List<TextPosition> labelPositions = List.of();
		if (!originalLabel.isEmpty()) {
			labelPositions = locateLabel(source, pageNumber, region, originalLabel);
		}
		LayerUtility layers = new LayerUtility(output);
		PDFormXObject form = layers.importPageAsForm(source, pageNumber - 1);

		// Establish a consistent crop-relative coordinate system.
		// The imported form retains the original page content
		// coordinates, so its crop-box origin must map to (0, 0).
		form.setMatrix(AffineTransform.getTranslateInstance(-cropBox.getLowerLeftX(), -cropBox.getLowerLeftY()));
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

	/**
	 * Reproduces a captured region and optionally replaces a printed identifier
	 * using the supplied destination font.
	 *
	 * <p>
	 * The search can be restricted to a smaller rectangle within the captured
	 * region, such as a marking-guide identifier column. Replacement changes
	 * visible content, not underlying PDF text.
	 *
	 * @param source           source PDF document
	 * @param pageNumber       one-based source page number
	 * @param output           destination PDF document
	 * @param stream           destination content stream
	 * @param region           captured source rectangle
	 * @param searchRegion     rectangle within which to locate the identifier
	 * @param destinationX     destination left coordinate
	 * @param destinationY     destination bottom coordinate
	 * @param originalLabels   alternative original identifier strings
	 * @param replacementLabel replacement identifier, or empty to remove
	 * @param replacementFont  destination font
	 * @param required         whether a matching identifier must be found
	 * @throws IOException if reproduction fails or a required identifier cannot be
	 *                     located
	 */
	public static void appendRegionWithLabel(PDDocument source, int pageNumber, PDDocument output,
			PDPageContentStream stream, PDRectangle region, PDRectangle searchRegion, float destinationX,
			float destinationY, List<String> originalLabels, String replacementLabel, PDFont replacementFont,
			boolean required) throws IOException {
		List<TextPosition> match = List.of();
		for (String label : originalLabels) {
			try {
				List<TextPosition> candidate = locateLabel(source, pageNumber, searchRegion, label);
				TextPosition first = candidate.getFirst();

				// Prefer the earliest visible identifier when
				// several supported label formats are present.
				if (match.isEmpty() || first.getYDirAdj() < match.getFirst().getYDirAdj()
						|| (Math.abs(first.getYDirAdj() - match.getFirst().getYDirAdj()) < 0.1f
								&& first.getXDirAdj() < match.getFirst().getXDirAdj())) {
					match = candidate;
				}
			} catch (IOException failure) {
				if (!("Cannot locate part label: " + label).equals(failure.getMessage())) {
					throw failure;
				}
			}
		}
		if (required && match.isEmpty()) {
			throw new IOException("Cannot locate original identifier: " + originalLabels);
		}
		appendRegion(source, pageNumber, output, stream, region, destinationX, destinationY, "", "");
		if (match.isEmpty()) {
			return;
		}
		TextPosition first = match.getFirst();
		TextPosition last = match.getLast();
		float pageHeight = source.getPage(pageNumber - 1).getCropBox().getHeight();
		float x = destinationX + first.getXDirAdj() - region.getLowerLeftX();
		float baseline = destinationY + pageHeight - first.getYDirAdj() - region.getLowerLeftY();
		float width = last.getXDirAdj() + last.getWidthDirAdj() - first.getXDirAdj();
		float fontSize = first.getFontSizeInPt();
		stream.saveGraphicsState();

		// Keep masking inside the captured region and minimise
		// interference with nearby text and table borders.
		stream.addRect(destinationX, destinationY, region.getWidth(), region.getHeight());
		stream.clip();
		stream.setNonStrokingColor(1.0f);
		stream.addRect(x - 0.75f, baseline - 2, width + 1.5f, first.getHeightDir() + 3);
		stream.fill();
		if (!replacementLabel.isEmpty()) {
			stream.setNonStrokingColor(0.0f);
			stream.beginText();
			stream.setFont(replacementFont, fontSize);
			stream.newLineAtOffset(x, baseline);
			stream.showText(replacementLabel);
			stream.endText();
		}
		stream.restoreGraphicsState();
	}

	static String findQuestionPartLabel(PDDocument source, int pageNumber, PDRectangle region, String originalLabel,
			boolean allowBareLabel) throws IOException {
		if (originalLabel.isEmpty()) {
			return "";
		}
		List<String> alternatives = List.of(originalLabel, originalLabel.replace("(", ""));
		for (String alternative : alternatives) {
			if (!findOptionalLabel(source, pageNumber, region, alternative).isEmpty()) {
				return alternative;
			}
		}
		if (allowBareLabel) {
			String bareLabel = originalLabel.replace("(", "").replace(")", "").trim();
			if (bareLabel.matches("[a-z]")) {
				PDRectangle searchRegion = questionPartLabelSearchRegion(source, pageNumber, region, bareLabel);
				if (searchRegion != null && !findOptionalLabel(source, pageNumber, searchRegion, bareLabel).isEmpty()) {
					return bareLabel;
				}
			}
		}
		return "";
	}

	static PDRectangle questionPartLabelSearchRegion(PDDocument source, int pageNumber, PDRectangle region,
			String originalLabel) {
		if (!originalLabel.matches("[a-z]")) {
			return region;
		}
		PDRectangle cropBox = source.getPage(pageNumber - 1).getCropBox();
		float pageWidth = cropBox.getWidth();

		// Oxford's verified bare-letter identifiers occupy
		// a narrow column approximately 6% across the page.
		// Do not search ordinary Question text for bare letters.
		float left = Math.max(region.getLowerLeftX(), pageWidth * 0.055f);
		float right = Math.min(region.getUpperRightX(), pageWidth * 0.067f);
		float top = region.getUpperRightY();
		float bottom = Math.max(region.getLowerLeftY(), top - 35);
		if (right <= left || top <= bottom) {
			return null;
		}
		return new PDRectangle(left, bottom, right - left, top - bottom);
	}

	static float sampleResponseColumnLeft(PDDocument source, int pageNumber) throws IOException {
		PDRectangle cropBox = source.getPage(pageNumber - 1).getCropBox();
		float pageWidth = cropBox.getWidth();
		float pageHeight = cropBox.getHeight();
		PDRectangle headerSearch = new PDRectangle(0, Math.max(0, pageHeight - 145), pageWidth * 0.30f,
				Math.min(145, pageHeight));
		List<TextPosition> sample = findOptionalLabel(source, pageNumber, headerSearch, "Sample");
		if (sample.isEmpty()) {
			return Float.NaN;
		}
		TextPosition first = sample.getFirst();
		float x = first.getXDirAdj();
		float baseline = pageHeight - first.getYDirAdj();
		if (x <= 7) {
			return Float.NaN;
		}

		// Confirm that Sample belongs to the table header,
		// rather than an unrelated occurrence in the document.
		PDRectangle precedingHeader = new PDRectangle(0, baseline - 2, x, 4);
		PDRectangle followingHeader = new PDRectangle(x, baseline - 2, Math.min(130, pageWidth - x), 4);
		if (findOptionalLabel(source, pageNumber, precedingHeader, "Q").isEmpty()) {
			return Float.NaN;
		}
		if (findOptionalLabel(source, pageNumber, followingHeader, "response").isEmpty()) {
			return Float.NaN;
		}

		// Reference QCAA tables position this header approximately
		// 5.6 points inside the Sample response column. Retain a
		// slightly wider left clearance to protect response content.
		return x - 7;
	}

	private static List<TextPosition> findOptionalLabel(PDDocument source, int pageNumber, PDRectangle region,
			String label) throws IOException {
		try {
			return locateLabel(source, pageNumber, region, label);
		} catch (IOException failure) {
			if (("Cannot locate part label: " + label).equals(failure.getMessage())) {
				return List.of();
			}
			throw failure;
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
