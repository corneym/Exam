package au.edu.eq.questionbank.repository.curriculum;

import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.importer.curriculum.CurriculumExcelImporter;
import au.edu.eq.questionbank.importer.curriculum.CurriculumImportRow;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class SqliteCurriculumHierarchyImportTest {

	@TempDir
	Path tempDirectory;

	private List<CurriculumImportRow> comparisonRows() {
		return List.of(new CurriculumImportRow("1", "Unit one"),
				new CurriculumImportRow("1.1", "Topic one"), new CurriculumImportRow("1.2", "Topic two"),
				new CurriculumImportRow("1.1.1", "Descriptor one"),
				new CurriculumImportRow("1.1.2", "Descriptor two"));
	}

	private List<String> databaseSnapshot(SqliteDatabase database) throws Exception {
		List<String> snapshot = new ArrayList<>();
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery("""
					SELECT id, subject_name
					FROM subjects
					ORDER BY id
					""")) {
				while (result.next()) {
					snapshot.add("subject|" + result.getLong("id") + "|" + result.getString("subject_name"));
				}
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT id, subject_id, syllabus_name, is_current
					FROM syllabus_versions
					ORDER BY id
					""")) {
				while (result.next()) {
					snapshot.add("version|" + result.getLong("id") + "|" + result.getLong("subject_id") + "|"
							+ result.getString("syllabus_name") + "|" + result.getInt("is_current"));
				}
			}
			try (ResultSet result = statement.executeQuery("""
					SELECT id, syllabus_version_id, parent_id, curriculum_code,
					       curriculum_name, curriculum_level, display_order
					FROM curriculum_nodes
					ORDER BY id
					""")) {
				while (result.next()) {
					long parentId = result.getLong("parent_id");
					String parent = result.wasNull() ? "null" : Long.toString(parentId);
					snapshot.add("node|" + result.getLong("id") + "|" + result.getLong("syllabus_version_id")
							+ "|" + parent + "|" + result.getString("curriculum_code") + "|"
							+ result.getString("curriculum_name") + "|" + result.getString("curriculum_level")
							+ "|" + result.getInt("display_order"));
				}
			}
		}
		return snapshot;
	}

	private CurriculumImportConflictException assertConflictLeavesDatabaseUnchanged(SqliteDatabase database,
			SqliteCurriculumImporter importer, boolean current, List<CurriculumImportRow> rows) throws Exception {
		List<String> before = databaseSnapshot(database);
		CurriculumImportConflictException exception = assertThrows(CurriculumImportConflictException.class,
				() -> importer.importSyllabusWithResult("Chemistry", "2019", current, rows));
		assertEquals(before, databaseSnapshot(database));
		return exception;
	}

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
		CurriculumImportResult repeatedImport = sqliteImporter.importSyllabusWithResult("Chemistry", "2025", true,
				excelImporter.read(excelFile));
		assertFalse(repeatedImport.imported());
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

	@Test
	void retainsHistoricalAndCurrentCurriculumVersions() throws Exception {
		Path databasePath = tempDirectory.resolve("multiple-versions.db");
		SqliteDatabase database = new SqliteDatabase(databasePath);
		database.initialiseSchema();
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database, writer);
		List<CurriculumImportRow> rows2019 = List.of(new CurriculumImportRow("1", "2019 Unit one"),
				new CurriculumImportRow("1.1", "2019 Topic one"), new CurriculumImportRow("1.1.1", "2019 Subtopic one"),
				new CurriculumImportRow("1.1.1.1", "2019 Descriptor"));
		List<CurriculumImportRow> rows2025 = List.of(new CurriculumImportRow("1", "2025 Unit one"),
				new CurriculumImportRow("1.1", "2025 Topic one"), new CurriculumImportRow("1.1.1", "2025 Subtopic one"),
				new CurriculumImportRow("1.1.1.1", "2025 Descriptor"));
		importer.importSyllabus("Chemistry", "2019", false, rows2019);
		importer.importSyllabus("Chemistry", "2025", true, rows2025);
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		List<Subject> subjects = repository.findAllSubjects();
		assertEquals(1, subjects.size());
		Subject chemistry = subjects.get(0);
		assertEquals("Chemistry", chemistry.getName());
		List<SyllabusVersion> versions = repository.findVersionsForSubject(chemistry);
		assertEquals(2, versions.size());
		SyllabusVersion version2019 = versions.get(0);
		SyllabusVersion version2025 = versions.get(1);
		assertEquals("2019", version2019.getName());
		assertFalse(version2019.isCurrent());
		assertEquals("2025", version2025.getName());
		assertTrue(version2025.isCurrent());
		Optional<CurriculumNode> node2019 = repository.findByCode(version2019, "1.1.1.1");
		Optional<CurriculumNode> node2025 = repository.findByCode(version2025, "1.1.1.1");
		assertTrue(node2019.isPresent());
		assertTrue(node2025.isPresent());
		assertEquals("2019 Descriptor", node2019.get().getName());
		assertEquals("2025 Descriptor", node2025.get().getName());
	}

	@Test
	void reimportingIdenticalCurriculumIsANoOp() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("identical-reimport.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));

		CurriculumImportResult first = importer.importSyllabusWithResult("Chemistry", "2019", false,
				comparisonRows());
		List<String> afterFirstImport = databaseSnapshot(database);
		CurriculumImportResult second = importer.importSyllabusWithResult("Chemistry", "2019", false,
				comparisonRows());

		assertTrue(first.imported());
		assertFalse(second.imported());
		assertEquals(first.syllabusVersion().getId(), second.syllabusVersion().getId());
		assertEquals(afterFirstImport, databaseSnapshot(database));
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT
						    (SELECT COUNT(*) FROM subjects) AS subject_count,
						    (SELECT COUNT(*) FROM syllabus_versions) AS version_count,
						    (SELECT COUNT(*) FROM curriculum_nodes) AS node_count
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt("subject_count"));
			assertEquals(1, result.getInt("version_count"));
			assertEquals(comparisonRows().size(), result.getInt("node_count"));
		}
	}

	@Test
	void rejectsChangedDescriptorTextWithoutChangingStoredCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("changed-text.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		List<CurriculumImportRow> changedRows = List.of(new CurriculumImportRow("1", "Unit one"),
				new CurriculumImportRow("1.1", "Topic one"), new CurriculumImportRow("1.2", "Topic two"),
				new CurriculumImportRow("1.1.1", "Changed descriptor"),
				new CurriculumImportRow("1.1.2", "Descriptor two"));

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, false,
				changedRows);

		assertTrue(exception.getMessage().contains("curriculum code 1.1.1 differs"));
	}

	@Test
	void rejectsAddedNodeWithoutChangingStoredCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("added-node.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		List<CurriculumImportRow> changedRows = new ArrayList<>(comparisonRows());
		changedRows.add(new CurriculumImportRow("1.2.1", "Added descriptor"));

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, false,
				changedRows);

		assertTrue(exception.getMessage().contains("adds curriculum code 1.2.1"));
	}

	@Test
	void rejectsMissingNodeWithoutChangingStoredCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("missing-node.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		List<CurriculumImportRow> changedRows = comparisonRows().subList(0, comparisonRows().size() - 1);

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, false,
				changedRows);

		assertTrue(exception.getMessage().contains("omits curriculum code 1.1.2"));
	}

	@Test
	void rejectsChangedParentRelationshipWithoutChangingStoredCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("changed-parent.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					UPDATE curriculum_nodes
					SET parent_id = (
					    SELECT id
					    FROM curriculum_nodes
					    WHERE curriculum_code = '1.2'
					)
					WHERE curriculum_code = '1.1.1'
					""");
		}

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, false,
				comparisonRows());

		assertTrue(exception.getMessage().contains("curriculum code 1.1.1 differs"));
	}

	@Test
	void rejectsChangedDisplayOrderWithoutChangingStoredCurriculum() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("changed-order.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		List<CurriculumImportRow> reorderedRows = List.of(new CurriculumImportRow("1", "Unit one"),
				new CurriculumImportRow("1.1", "Topic one"), new CurriculumImportRow("1.2", "Topic two"),
				new CurriculumImportRow("1.1.2", "Descriptor two"),
				new CurriculumImportRow("1.1.1", "Descriptor one"));

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, false,
				reorderedRows);

		assertTrue(exception.getMessage().contains("curriculum code 1.1.1 differs"));
	}

	@Test
	void rejectsCurrentStatusConflictWithoutChangingAnySyllabus() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("current-conflict.db"));
		database.initialiseSchema();
		SqliteCurriculumImporter importer = new SqliteCurriculumImporter(database,
				new SqliteCurriculumWriter(database));
		importer.importSyllabus("Chemistry", "2019", false, comparisonRows());
		importer.importSyllabus("Chemistry", "2025", true, comparisonRows());

		CurriculumImportConflictException exception = assertConflictLeavesDatabaseUnchanged(database, importer, true,
				comparisonRows());

		assertTrue(exception.getMessage().contains("already imported as historical"));
		assertTrue(exception.getMessage().contains("cannot be re-imported as current"));
	}
}
