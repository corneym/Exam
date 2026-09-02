package au.edu.eq.questionbank.importer.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyQuestionWorkbookReaderTest {

	@TempDir
	Path tempDirectory;

	private Path createWorkbook() throws Exception {
		Path path = tempDirectory.resolve("legacy.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Year");
			header.createCell(1).setCellValue("Paper");
			header.createCell(2).setCellValue("Question");
			header.createCell(3).setCellValue("Marks");
			header.createCell(4).setCellValue("Topic");
			header.createCell(5).setCellValue("Answer");
			header.createCell(6).setCellValue("Preamble");

			Row first = sheet.createRow(1);
			first.createCell(0).setCellValue(2020);
			first.createCell(1).setCellValue("MCQ");
			first.createCell(2).setCellValue(1);
			first.createCell(3).setCellValue(1);
			first.createCell(4).setCellValue("1.1.1");
			first.createCell(5).setCellValue("B");

			Row second = sheet.createRow(2);
			second.createCell(0).setCellValue(2020);
			second.createCell(1).setCellValue("1");
			second.createCell(2).setCellValue("21a");
			second.createCell(3).setCellValue(3);
			second.createCell(4).setCellValue("2.3.1");
			second.createCell(6).setCellValue(1);

			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		return path;
	}

	@Test
	void readsLegacyQuestionRowsAndSheetProvider() throws Exception {
		Path workbookPath = createWorkbook();
		List<LegacyQuestionSheet> sheets = new LegacyQuestionWorkbookReader().read(workbookPath);
		assertEquals(1, sheets.size());
		LegacyQuestionSheet sheet = sheets.getFirst();
		assertEquals("QCAA", sheet.providerName());
		assertEquals(2, sheet.questions().size());

		LegacyQuestionRow first = sheet.questions().get(0);
		assertEquals(2020, first.year());
		assertEquals("MCQ", first.paperCode());
		assertEquals("1", first.questionCode());
		assertEquals(1, first.marks());
		assertEquals("1.1.1", first.classificationCode());
		assertEquals("B", first.answer());
		assertFalse(first.preambleCaptureRequired());

		LegacyQuestionRow second = sheet.questions().get(1);
		assertEquals("21a", second.questionCode());
		assertEquals(3, second.marks());
		assertNull(second.answer());
		assertTrue(second.preambleCaptureRequired());
	}

	@Test
	void rejectsInvalidMarksWithRowNumber() throws Exception {
		Path path = createWorkbook();
		try (Workbook workbook = WorkbookFactory.create(Files.newInputStream(path))) {
			workbook.getSheetAt(0).getRow(1).getCell(3).setCellValue("two");
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionWorkbookReader().read(path));
		assertTrue(exception.getMessage().contains("row 2"));
		assertTrue(exception.getMessage().contains("Marks"));
	}

	@Test
	void rejectsInvalidPreambleValueWithRowNumber() throws Exception {
		Path path = createWorkbook();
		try (Workbook workbook = WorkbookFactory.create(Files.newInputStream(path))) {
			workbook.getSheetAt(0).getRow(1).createCell(6).setCellValue("yes");
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionWorkbookReader().read(path));
		assertTrue(exception.getMessage().contains("row 2"));
		assertTrue(exception.getMessage().contains("Preamble"));
	}

	@Test
	void rejectsMissingRequiredColumn() throws Exception {
		Path path = tempDirectory.resolve("missing-column.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("QCAA");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Year");
			try (OutputStream output = Files.newOutputStream(path)) {
				workbook.write(output);
			}
		}
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new LegacyQuestionWorkbookReader().read(path));
		assertTrue(exception.getMessage().contains("Missing column"));
		assertTrue(exception.getMessage().contains("QCAA"));
	}
}