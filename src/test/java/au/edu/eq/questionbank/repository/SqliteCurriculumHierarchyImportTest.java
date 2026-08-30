package au.edu.eq.questionbank.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.importer.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.CurriculumImportRow;

class SqliteCurriculumHierarchyImportTest {

	@TempDir
	Path tempDirectory;

	private void assertNode(Connection connection, String code, String expectedLevel, String expectedParentCode)
			throws Exception {

		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    node.curriculum_level,
				    parent.curriculum_code AS parent_code
				FROM curriculum_nodes node
				LEFT JOIN curriculum_nodes parent
				    ON parent.id = node.parent_id
				WHERE node.curriculum_code = ?
				""")) {
			statement.setString(1, code);
			try (ResultSet result = statement.executeQuery()) {
				assertEquals(true, result.next());
				assertEquals(expectedLevel, result.getString("curriculum_level"));
				String parentCode = result.getString("parent_code");
				if (expectedParentCode == null) {
					assertEquals(null, parentCode);
				} else {
					assertNotNull(parentCode);
					assertEquals(expectedParentCode, parentCode);
				}
			}
		}
	}

	@Test
	void importsCurriculumFromExcelIntoSqlite() throws Exception {
		Path excelFile = tempDirectory.resolve("curriculum.xlsx");
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Curriculum");

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("Code");
			header.createCell(1).setCellValue("Content");

			Row unit = sheet.createRow(1);
			unit.createCell(0).setCellValue("1");
			unit.createCell(1).setCellValue("Chemical fundamentals");

			Row topic = sheet.createRow(2);
			topic.createCell(0).setCellValue("1.1");
			topic.createCell(1).setCellValue("Atomic structure");

			Row subtopic = sheet.createRow(3);
			subtopic.createCell(0).setCellValue("1.1.1");
			subtopic.createCell(1).setCellValue("Atomic models");

			Row descriptor = sheet.createRow(4);
			descriptor.createCell(0).setCellValue("1.1.1.1");
			descriptor.createCell(1).setCellValue("Describe the structure of an atom");

			try (OutputStream output = Files.newOutputStream(excelFile)) {
				workbook.write(output);
			}
		}
		CurriculumExcelImporter excelImporter = new CurriculumExcelImporter();
		List<CurriculumImportRow> rows = excelImporter.read(excelFile);
		Path databasePath = tempDirectory.resolve("excel-import.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter sqliteImporter = new SqliteCurriculumImporter(database, writer);
		sqliteImporter.importSyllabus("Chemistry", "2025", true, rows);
		try (Connection connection = database.openConnection()) {
			assertNode(connection, "1", "UNIT", null);
			assertNode(connection, "1.1", "TOPIC", "1");
			assertNode(connection, "1.1.1", "SUBTOPIC", "1.1");
			assertNode(connection, "1.1.1.1", "DESCRIPTOR", "1.1.1");
		}
	}

	@Test
	void importsFourLevelCurriculumHierarchy() throws Exception {
		Path databasePath = tempDirectory.resolve("curriculum.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		List<CurriculumImportRow> rows = List.of(new CurriculumImportRow("4", "Structure and synthesis"),
				new CurriculumImportRow("4.2", "Organic materials"),
				new CurriculumImportRow("4.2.2", "Organic reactions"),
				new CurriculumImportRow("4.2.2.1", "Describe addition reactions"),
				new CurriculumImportRow("4.2.2.2", "Explain substitution reactions"));
		importer.importSyllabus("Chemistry", "2025", true, rows);
		try (Connection connection = database.openConnection()) {
			assertNode(connection, "4", "UNIT", null);
			assertNode(connection, "4.2", "TOPIC", "4");
			assertNode(connection, "4.2.2", "SUBTOPIC", "4.2");
			assertNode(connection, "4.2.2.1", "DESCRIPTOR", "4.2.2");
			assertNode(connection, "4.2.2.2", "DESCRIPTOR", "4.2.2");
		}
	}

	@Test
	void importsThreeLevelCurriculumHierarchy() throws Exception {
		Path databasePath = tempDirectory.resolve("three-level.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		List<CurriculumImportRow> rows = List.of(new CurriculumImportRow("2", "Individual development"),
				new CurriculumImportRow("2.3", "Psychological disorders"),
				new CurriculumImportRow("2.3.5", "Describe approaches to diagnosis"));
		importer.importSyllabus("Psychology", "2025", true, rows);
		try (Connection connection = database.openConnection()) {
			assertNode(connection, "2", "UNIT", null);
			assertNode(connection, "2.3", "TOPIC", "2");
			assertNode(connection, "2.3.5", "DESCRIPTOR", "2.3");
		}
	}
}