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

	@Test
	void readsStudyChecklistAndFillsDownHierarchy() throws IOException {
		Path file = tempDirectory.resolve("curriculum.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Unit 3 and 4");

			Row title = sheet.createRow(0);
			title.createCell(1).setCellValue("CHEMISTRY STUDY GUIDE");

			Row header = sheet.createRow(2);
			header.createCell(1).setCellValue("Unit");
			header.createCell(2).setCellValue("Topic");
			header.createCell(3).setCellValue("Subtopic");
			// Column E deliberately has no heading.
			header.createCell(5).setCellValue("Descriptor");

			Row first = sheet.createRow(3);
			first.createCell(1).setCellValue("3. Equilibrium, Acids and Redox Reactions");
			first.createCell(2).setCellValue("Topic 1: Chemical Equilibrium Systems");
			first.createCell(3).setCellValue("Chemical equilibrium");
			first.createCell(4).setCellValue("3.1.1");
			first.createCell(5).setCellValue("First descriptor");

			Row second = sheet.createRow(4);
			second.createCell(5).setCellValue("Second descriptor");

			Row third = sheet.createRow(5);
			third.createCell(3).setCellValue("Factors that affect equilibrium");
			third.createCell(4).setCellValue("3.1.2");
			third.createCell(5).setCellValue("Third descriptor");

			try (OutputStream outputStream = Files.newOutputStream(file)) {
				workbook.write(outputStream);
			}
		}

		CurriculumExcelImporter importer = new CurriculumExcelImporter();

		List<CurriculumImportRow> rows = importer.read(file);

		assertEquals(3, rows.size());

		assertEquals("3.1.1", rows.get(0).classificationCode());
		assertEquals("First descriptor", rows.get(0).descriptor());

		assertEquals("3.1.1", rows.get(1).classificationCode());
		assertEquals("Chemical equilibrium", rows.get(1).subtopicName());
		assertEquals("Second descriptor", rows.get(1).descriptor());

		assertEquals("3.1.2", rows.get(2).classificationCode());
		assertEquals("Factors that affect equilibrium", rows.get(2).subtopicName());
	}

	@Test
	void readsExplicitClassificationColumn() throws IOException {
		Path file = tempDirectory.resolve("normalised.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Unit");
			header.createCell(1).setCellValue("Topic");
			header.createCell(2).setCellValue("Subtopic");
			header.createCell(3).setCellValue("Classification");
			header.createCell(4).setCellValue("Descriptor");

			Row data = sheet.createRow(1);
			data.createCell(0).setCellValue("Unit 3");
			data.createCell(1).setCellValue("Topic 1");
			data.createCell(2).setCellValue("Chemical equilibrium");
			data.createCell(3).setCellValue("3.1.1");
			data.createCell(4).setCellValue("Descriptor");

			try (OutputStream outputStream = Files.newOutputStream(file)) {
				workbook.write(outputStream);
			}
		}

		CurriculumExcelImporter importer = new CurriculumExcelImporter();

		List<CurriculumImportRow> rows = importer.read(file);

		assertEquals(1, rows.size());
		assertEquals("3.1.1", rows.get(0).classificationCode());
	}

	@Test
	void rejectsADescriptorBeforeTheHierarchyIsComplete() throws IOException {
		Path file = tempDirectory.resolve("incomplete.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Unit");
			header.createCell(1).setCellValue("Topic");
			header.createCell(2).setCellValue("Subtopic");
			header.createCell(3).setCellValue("Classification");
			header.createCell(4).setCellValue("Descriptor");
			sheet.createRow(1).createCell(4).setCellValue("Orphan descriptor");
			writeWorkbook(workbook, file);
		}

		assertThrows(IllegalArgumentException.class, () -> new CurriculumExcelImporter().read(file));
	}

	@Test
	void ignoresHiddenSheetsAndCleansImportedText() throws IOException {
		Path file = tempDirectory.resolve("visible-only.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet hidden = workbook.createSheet("Hidden");
			createNormalisedSheetContent(hidden, "Hidden descriptor");
			workbook.setSheetHidden(0, true);

			Sheet visible = workbook.createSheet("Visible");
			createNormalisedSheetContent(visible, "Visible\u00A0descriptor");
			writeWorkbook(workbook, file);
		}

		List<CurriculumImportRow> rows = new CurriculumExcelImporter().read(file);

		assertEquals(1, rows.size());
		assertEquals("Visible descriptor", rows.getFirst().descriptor());
	}

	private void createNormalisedSheetContent(Sheet sheet, String descriptor) {
		Row header = sheet.createRow(0);
		header.createCell(0).setCellValue("Unit");
		header.createCell(1).setCellValue("Topic");
		header.createCell(2).setCellValue("Subtopic");
		header.createCell(3).setCellValue("Classification");
		header.createCell(4).setCellValue("Descriptor");

		Row data = sheet.createRow(1);
		data.createCell(0).setCellValue("Unit 3");
		data.createCell(1).setCellValue("Topic 1");
		data.createCell(2).setCellValue("Chemical equilibrium");
		data.createCell(3).setCellValue("3.1.1");
		data.createCell(4).setCellValue(descriptor);
	}

	private void writeWorkbook(Workbook workbook, Path file) throws IOException {
		try (OutputStream outputStream = Files.newOutputStream(file)) {
			workbook.write(outputStream);
		}
	}
}
