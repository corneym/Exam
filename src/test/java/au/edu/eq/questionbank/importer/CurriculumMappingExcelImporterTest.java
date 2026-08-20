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

class CurriculumMappingExcelImporterTest {

	@TempDir
	Path tempDirectory;

	@Test
	void acceptsNewAlongsideValid2019Descriptor() throws IOException {

		Path file = tempDirectory.resolve("mixed.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row data = sheet.createRow(1);
			data.createCell(0).setCellValue("3.2.2.1");
			data.createCell(1).setCellValue("NEW, 3.1.4.2");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		List<CurriculumMappingImportRow> rows = importer.read(file);

		assertEquals(List.of(new CurriculumMappingImportRow("3.1.4", "3.2.2")), rows);
	}

	@Test
	void convertsDescriptorMappingsToSubtopicMappings() throws IOException {

		Path file = tempDirectory.resolve("mapping.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row first = sheet.createRow(1);
			first.createCell(0).setCellValue("3.2.1.1");
			first.createCell(1).setCellValue("3.1.2.4");

			Row second = sheet.createRow(2);
			second.createCell(0).setCellValue("3.2.1.2");
			second.createCell(1).setCellValue("3.1.2.5");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		List<CurriculumMappingImportRow> rows = importer.read(file);

		assertEquals(List.of(new CurriculumMappingImportRow("3.1.2", "3.2.1")), rows);
	}

	@Test
	void handlesMultiple2019DescriptorsInOneCell() throws IOException {

		Path file = tempDirectory.resolve("multiple.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row data = sheet.createRow(1);
			data.createCell(0).setCellValue("4.2.1.3");
			data.createCell(1).setCellValue("4.1.2.1, 4.1.3.2");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		List<CurriculumMappingImportRow> rows = importer.read(file);

		assertEquals(List.of(new CurriculumMappingImportRow("4.1.2", "4.2.1"),
				new CurriculumMappingImportRow("4.1.3", "4.2.1")), rows);
	}

	@Test
	void ignoresNew2025Descriptors() throws IOException {
		Path file = tempDirectory.resolve("new.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row mapped = sheet.createRow(1);
			mapped.createCell(0).setCellValue("3.2.1.1");
			mapped.createCell(1).setCellValue("3.1.2.4");

			Row newDescriptor = sheet.createRow(2);
			newDescriptor.createCell(0).setCellValue("3.2.1.2");
			newDescriptor.createCell(1).setCellValue("NEW");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		List<CurriculumMappingImportRow> rows = importer.read(file);

		assertEquals(List.of(new CurriculumMappingImportRow("3.1.2", "3.2.1")), rows);
	}

	@Test
	void rejectsUnrecognised2019MappingValue() throws IOException {

		Path file = tempDirectory.resolve("invalid.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row data = sheet.createRow(1);
			data.createCell(0).setCellValue("3.2.1.1");
			data.createCell(1).setCellValue("unknown");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		assertThrows(IllegalArgumentException.class, () -> importer.read(file));
	}

	@Test
	void treatsNoAsNew() throws IOException {
		Path file = tempDirectory.resolve("no.xlsx");

		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Mapping");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("2025");
			header.createCell(1).setCellValue("2019");

			Row mapped = sheet.createRow(1);
			mapped.createCell(0).setCellValue("3.2.1.1");
			mapped.createCell(1).setCellValue("3.1.2.4");

			Row noEquivalent = sheet.createRow(2);
			noEquivalent.createCell(0).setCellValue("3.2.1.2");
			noEquivalent.createCell(1).setCellValue("NO");

			try (OutputStream output = Files.newOutputStream(file)) {
				workbook.write(output);
			}
		}

		CurriculumMappingExcelImporter importer = new CurriculumMappingExcelImporter();

		List<CurriculumMappingImportRow> rows = importer.read(file);

		assertEquals(1, rows.size());
	}
}