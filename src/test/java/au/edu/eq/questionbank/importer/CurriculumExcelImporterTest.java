package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurriculumExcelImporterTest {

	@TempDir
	Path tempDirectory;

	private void addHeader(Sheet sheet) {

		Row header = sheet.createRow(0);

		header.createCell(0).setCellValue("Code");

		header.createCell(1).setCellValue("Content");
	}

	private void addRow(Sheet sheet, int rowIndex, String code, String content) {

		Row row = sheet.createRow(rowIndex);

		row.createCell(0).setCellValue(code);

		row.createCell(1).setCellValue(content);
	}

	private void writeWorkbook(Workbook workbook, Path file) throws IOException {

		try (OutputStream outputStream = Files.newOutputStream(file)) {

			workbook.write(outputStream);
		}
	}

	@Test
	void ignoresBlankRows() throws IOException {

		Path file = tempDirectory.resolve("blank-rows.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "1", "Unit 1");

			sheet.createRow(2);

			addRow(sheet, 3, "1.1", "Topic 1");
			addRow(sheet, 4, "1.1.1", "Descriptor");

			writeWorkbook(workbook, file);
		}

		List<CurriculumImportRow> rows = new CurriculumExcelImporter().read(file);

		assertEquals(3, rows.size());
	}

	@Test
	void ignoresHiddenSheets() throws IOException {

		Path file = tempDirectory.resolve("hidden.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {

			Sheet hidden = workbook.createSheet("Hidden");
			addHeader(hidden);
			addRow(hidden, 1, "9", "Hidden unit");

			workbook.setSheetHidden(0, true);

			Sheet visible = workbook.createSheet("Curriculum");
			addHeader(visible);
			addRow(visible, 1, "1", "Visible unit");

			writeWorkbook(workbook, file);
		}

		List<CurriculumImportRow> rows = new CurriculumExcelImporter().read(file);

		assertEquals(1, rows.size());
		assertEquals("1", rows.get(0).code());
		assertEquals("Visible unit", rows.get(0).content());
	}

	@Test
	void preservesMathMarkupInContent() throws IOException {

		Path file = tempDirectory.resolve("maths.xlsx");

		String mathContent = "Use <math><msup><mi>x</mi><mn>2</mn></msup></math>";

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "4", "Further calculus");
			addRow(sheet, 2, "4.1", "Integration techniques");
			addRow(sheet, 3, "4.1.1", mathContent);

			writeWorkbook(workbook, file);
		}

		List<CurriculumImportRow> rows = new CurriculumExcelImporter().read(file);

		assertEquals(mathContent, rows.get(2).content());
	}

	@Test
	void readsTwoColumnCurriculumWorkbook() throws IOException {

		Path file = tempDirectory.resolve("curriculum.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "1", "Unit 1");
			addRow(sheet, 2, "1.1", "Topic 1");
			addRow(sheet, 3, "1.1.1", "Descriptor one");
			addRow(sheet, 4, "1.1.2", "Descriptor two");

			writeWorkbook(workbook, file);
		}

		CurriculumExcelImporter importer = new CurriculumExcelImporter();

		List<CurriculumImportRow> rows = importer.read(file);

		assertEquals(4, rows.size());

		assertEquals("1", rows.get(0).code());
		assertEquals("Unit 1", rows.get(0).content());

		assertEquals("1.1", rows.get(1).code());
		assertEquals("Topic 1", rows.get(1).content());

		assertEquals("1.1.1", rows.get(2).code());
		assertEquals("Descriptor one", rows.get(2).content());
	}

	@Test
	void rejectsCodeDeeperThanFourLevels() throws IOException {

		Path file = tempDirectory.resolve("too-deep.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "1.2.3.4.5", "Invalid curriculum level");

			writeWorkbook(workbook, file);
		}

		assertThrows(IllegalArgumentException.class, () -> new CurriculumExcelImporter().read(file));
	}

	@Test
	void rejectsDuplicateCodes() throws IOException {

		Path file = tempDirectory.resolve("duplicates.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "1", "Unit 1");
			addRow(sheet, 2, "1.1", "First topic");
			addRow(sheet, 3, "1.1", "Duplicate topic");

			writeWorkbook(workbook, file);
		}

		assertThrows(IllegalArgumentException.class, () -> new CurriculumExcelImporter().read(file));
	}

	@Test
	void rejectsMissingCode() throws IOException {

		Path file = tempDirectory.resolve("missing-code.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "", "Content without code");

			writeWorkbook(workbook, file);
		}

		assertThrows(IllegalArgumentException.class, () -> new CurriculumExcelImporter().read(file));
	}

	@Test
	void rejectsMissingContent() throws IOException {

		Path file = tempDirectory.resolve("missing-content.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			addHeader(sheet);

			addRow(sheet, 1, "1", "");

			writeWorkbook(workbook, file);
		}

		assertThrows(IllegalArgumentException.class, () -> new CurriculumExcelImporter().read(file));
	}
}