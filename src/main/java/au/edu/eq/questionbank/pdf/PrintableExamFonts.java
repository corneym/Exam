package au.edu.eq.questionbank.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Embedded font variants used to reproduce examination typography.
 *
 * <p>
 * Student documents use Times New Roman, while marking guides use Arial. Font
 * instances belong to the destination PDF document into which they were loaded.
 *
 * @param regular    regular font
 * @param bold       bold font
 * @param italic     italic font
 * @param boldItalic bold italic font
 */
public record PrintableExamFonts(PDFont regular, PDFont bold, PDFont italic, PDFont boldItalic) {

	/**
	 * Creates a complete set of examination font variants.
	 *
	 * @param regular    regular font
	 * @param bold       bold font
	 * @param italic     italic font
	 * @param boldItalic bold italic font
	 * @throws NullPointerException if any font is null
	 */
	public PrintableExamFonts {
		Objects.requireNonNull(regular, "regular");
		Objects.requireNonNull(bold, "bold");
		Objects.requireNonNull(italic, "italic");
		Objects.requireNonNull(boldItalic, "boldItalic");
	}

	/**
	 * Loads Arial font variants for a marking guide.
	 *
	 * @param document      destination PDF document
	 * @param fontDirectory directory containing installed font files
	 * @return embedded Arial font variants
	 * @throws IOException if a required font cannot be loaded
	 */
	public static PrintableExamFonts loadMarkingGuide(PDDocument document, Path fontDirectory) throws IOException {
		return new PrintableExamFonts(loadFont(document, fontDirectory, "arial.ttf"),
				loadFont(document, fontDirectory, "arialbd.ttf"), loadFont(document, fontDirectory, "ariali.ttf"),
				loadFont(document, fontDirectory, "arialbi.ttf"));
	}

	/**
	 * Loads Times New Roman font variants for student documents.
	 *
	 * @param document      destination PDF document
	 * @param fontDirectory directory containing installed font files
	 * @return embedded Times New Roman font variants
	 * @throws IOException if a required font cannot be loaded
	 */
	public static PrintableExamFonts loadStudent(PDDocument document, Path fontDirectory) throws IOException {
		return new PrintableExamFonts(loadFont(document, fontDirectory, "times.ttf"),
				loadFont(document, fontDirectory, "timesbd.ttf"), loadFont(document, fontDirectory, "timesi.ttf"),
				loadFont(document, fontDirectory, "timesbi.ttf"));
	}

	/**
	 * Returns the installed Windows font directory.
	 *
	 * @return Windows font directory
	 * @throws IOException if the Windows font directory cannot be located
	 */
	public static Path windowsFontDirectory() throws IOException {
		String windowsDirectory = System.getenv("WINDIR");
		if (windowsDirectory == null || windowsDirectory.isBlank()) {
			throw new IOException("Cannot locate the Windows font directory.");
		}
		Path directory = Path.of(windowsDirectory, "Fonts");
		if (!Files.isDirectory(directory)) {
			throw new IOException("Font directory does not exist: " + directory);
		}
		return directory;
	}

	private static PDFont loadFont(PDDocument document, Path directory, String filename) throws IOException {
		Path path = directory.resolve(filename);
		if (!Files.isRegularFile(path)) {
			throw new IOException("Required examination font is unavailable: " + path);
		}

		// Load complete TrueType font data from the installed
		// font rather than reusing an incomplete PDF font subset.
		return PDType0Font.load(document, path.toFile());
	}
}
