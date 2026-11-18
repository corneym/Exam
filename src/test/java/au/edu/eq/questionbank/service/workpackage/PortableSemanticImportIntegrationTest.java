package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Decision;

class PortableSemanticImportIntegrationTest {

	@TempDir
	Path directory;

	static Stream<Arguments> invalidPackages() {
		return Stream.of(false, true)
				.flatMap(assignment -> Stream
						.of("UNIT", "TOPIC", "SYLLABUS", "SUBJECT", "SOURCE_BOOKLET", "REGION_BOOKLET", "MCQ_MARKS",
								"EMPTY_ANSWER", "REGION_BOUNDS", "EMPTY_CONTEXT", "BLANK_BOOKLET",
								"SAME_SYLLABUS_MAPPING", "SYLLABUS_STATE")
						.map(defect -> Arguments.of(assignment, defect)));
	}

	static Stream<Arguments> validPackages() {
		return Stream.of(false, true)
				.flatMap(assignment -> Stream.of("SUBTOPIC", "DESCRIPTOR", "LEGACY_2019", "REPEATED_REGION")
						.map(classification -> Arguments.of(assignment, classification)));
	}

	@ParameterizedTest
	@MethodSource("invalidPackages")
	void rejectsSemanticDefectsWithoutChangingDatabaseSessionLedgerOrAssets(boolean assignment, String defect)
			throws Exception {
		Fixture fixture = fixture(assignment, false);
		Path crafted = PortableArchiveEditor.rewrite(fixture.archive(), directory.resolve("crafted.examwork"),
				records -> corrupt(records, defect));

		// Prove manifest lengths, hashes, CRCs and record syntax are valid before
		// import.
		new ExamWorkArchiveValidator().validate(crafted, ApplicationVersion.current(),
				SqliteDatabase.latestSchemaVersion());
		SubjectImportApprovalPlan plan = assignment ? null : approve(fixture, crafted);
		List<String> before = databaseContents(fixture.database());
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> install(fixture, crafted, plan, assignment));
		assertTrue(failure.getMessage().toLowerCase(Locale.ROOT)
				.contains(expectedMessage(defect).toLowerCase(Locale.ROOT)), failure.getMessage());
		assertEquals(before, databaseContents(fixture.database()),
				"All persisted graph, ledger and session rows must remain unchanged");
		assertFalse(Files.exists(fixture.config().dataRoot().resolve("subjects/Biology/exams/questions.pdf")));
		assertEquals("unrelated", Files.readString(fixture.config().dataRoot().resolve("subjects/Chemistry/keep.txt")));
	}

	@ParameterizedTest
	@MethodSource("validPackages")
	void acceptsSupportedClassificationsAndReplaysWithoutDuplicatingGraph(boolean assignment, String classification)
			throws Exception {
		Fixture fixture = fixture(assignment, classification.equals("LEGACY_2019"));
		Path crafted = PortableArchiveEditor.rewrite(fixture.archive(), directory.resolve("valid.examwork"),
				records -> {
					if (classification.equals("SUBTOPIC")) {
						change(records, "QUESTION", "classification_node_id",
								node(records, "SUBTOPIC").recordId().toString());
					} else if (classification.equals("REPEATED_REGION")) {
						ExchangeRecord question = first(records, "QUESTION");
						ObjectNode values = (ObjectNode) question.values();
						var parts = (ArrayNode) values.path("question_content_parts");
						ObjectNode repeated = ((ObjectNode) parts.get(0)).deepCopy();
						repeated.put("content_order", 1);
						parts.add(repeated);
						replaceValues(records, question, values);
					}
				});
		SubjectImportApprovalPlan plan = assignment ? null : approve(fixture, crafted);
		assertFalse(install(fixture, crafted, plan, assignment));

		// Normal repository loading must reconstruct the imported domain object.
		long id;
		try (var connection = fixture.database().openConnection();
				var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT id FROM questions")) {
			assertTrue(rows.next());
			id = rows.getLong(1);
		}
		var question = new SqliteQuestionRepository(fixture.database()).findById(id).orElseThrow();
		assertEquals(classification.equals("SUBTOPIC") ? "SUBTOPIC" : "DESCRIPTOR",
				question.getClassification().getLevel().name());
		if (classification.equals("LEGACY_2019")) {
			assertEquals("Biology 2019", question.getClassification().getSyllabusVersion().getName());
		} else if (classification.equals("REPEATED_REGION")) {
			assertEquals(2, question.getRegions().size());
		}
		List<String> beforeReplay = databaseContents(fixture.database(), true);
		assertTrue(install(fixture, crafted, plan, assignment));
		assertEquals(beforeReplay, databaseContents(fixture.database(), true));
		assertEquals("unrelated", Files.readString(fixture.config().dataRoot().resolve("subjects/Chemistry/keep.txt")));
	}

	@Test
	void rejectionPreservesAnAlreadyActiveAssignmentAndItsPublishedAssets() throws Exception {
		Fixture fixture = fixture(true, false);
		install(fixture, fixture.archive(), null, true);
		List<String> before = databaseContents(fixture.database());
		Path source = fixture.config().dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		byte[] sourceContents = Files.readAllBytes(source);
		Path crafted = PortableArchiveEditor.rewrite(fixture.archive(), directory.resolve("invalid-replay.examwork"),
				records -> corrupt(records, "UNIT"));
		assertThrows(PackageFormatException.class, () -> install(fixture, crafted, null, true));
		assertEquals(before, databaseContents(fixture.database()));
		assertArrayEquals(sourceContents, Files.readAllBytes(source));
	}

	private String expectedMessage(String defect) {
		return switch (defect) {
		case "UNIT", "TOPIC" -> "SUBTOPIC or DESCRIPTOR";
		case "SYLLABUS" -> "same syllabus";
		case "SUBJECT" -> "Subject";
		case "SOURCE_BOOKLET" -> "source_question_id";
		case "REGION_BOOKLET" -> "question_regions";
		case "MCQ_MARKS" -> "exactly 1 mark";
		case "EMPTY_ANSWER" -> "Answer must contain";
		case "REGION_BOUNDS" -> "x + width";
		case "EMPTY_CONTEXT" -> "Shared Context must contain";
		case "BLANK_BOOKLET" -> "booklet_name";
		case "SAME_SYLLABUS_MAPPING" -> "different syllabuses";
		case "SYLLABUS_STATE" -> "curriculum_status";
		default -> throw new IllegalArgumentException(defect);
		};
	}

	private void corrupt(List<ExchangeRecord> records, String defect) {
		switch (defect) {
		case "UNIT", "TOPIC" ->
			change(records, "QUESTION", "classification_node_id", node(records, defect).recordId().toString());
		case "SYLLABUS" -> {
			ExchangeRecord version = records.stream().filter(
					r -> r.recordType().equals("SYLLABUS_VERSION") && r.values().path("is_current").asInt() == 0)
					.findFirst().orElseThrow();
			ExchangeRecord descriptor = node(records, "DESCRIPTOR");
			replace(records, descriptor, "syllabus_version_id", version.recordId().toString());
		}
		case "SUBJECT" -> {
			ExchangeRecord subject = first(records, "SUBJECT");
			ObjectNode values = (ObjectNode) subject.values();
			values.put("subject_name", "Physics");
			UUID id = UUID.randomUUID();
			records.add(new ExchangeRecord(1, "SUBJECT", id, UUID.randomUUID(), null, values));
			change(records, "SYLLABUS_VERSION", "subject_id", id.toString());
		}
		case "SOURCE_BOOKLET", "REGION_BOOKLET" -> {
			String owner = first(records, "QUESTION").values().path("booklet_id").asText();
			ExchangeRecord other = records.stream()
					.filter(r -> r.recordType().equals("EXAM_BOOKLET") && !r.recordId().toString().equals(owner))
					.findFirst().orElseThrow();
			if (defect.equals("SOURCE_BOOKLET")) {
				change(records, "SOURCE_QUESTION", "booklet_id", other.recordId().toString());
			} else {
				ExchangeRecord question = first(records, "QUESTION");
				ObjectNode values = (ObjectNode) question.values();
				((ObjectNode) values.path("question_regions").get(0)).put("booklet_id", other.recordId().toString());
				replaceValues(records, question, values);
			}
		}
		case "MCQ_MARKS" -> change(records, "QUESTION", "response_type", "MULTIPLE_CHOICE");
		case "BLANK_BOOKLET" -> change(records, "EXAM_BOOKLET", "booklet_name", " ");
		case "SYLLABUS_STATE" -> change(records, "SYLLABUS_VERSION", "curriculum_status", "FINAL");
		case "SAME_SYLLABUS_MAPPING" -> {
			ObjectNode values = JsonNodeFactory.instance.objectNode();
			String endpoint = node(records, "DESCRIPTOR").recordId().toString();
			values.put("source_node_id", endpoint);
			values.put("target_node_id", endpoint);
			values.put("mapping_status", "CONFIRMED");
			records.add(
					new ExchangeRecord(1, "CURRICULUM_MAPPING", UUID.randomUUID(), UUID.randomUUID(), null, values));
		}
		case "EMPTY_ANSWER" -> change(records, "ANSWER", "answer_text", " ");
		case "REGION_BOUNDS" -> {
			ExchangeRecord question = first(records, "QUESTION");
			ObjectNode values = (ObjectNode) question.values();
			((ObjectNode) values.path("question_regions").get(0)).put("width", 1.0);
			replaceValues(records, question, values);
		}
		case "EMPTY_CONTEXT" -> {
			ExchangeRecord context = first(records, "SHARED_QUESTION_CONTEXT");
			ObjectNode values = (ObjectNode) context.values();
			values.putArray("shared_question_context_regions");
			values.putArray("shared_question_context_content_parts");
			replaceValues(records, context, values);
		}
		default -> throw new IllegalArgumentException(defect);
		}
	}

	private void change(List<ExchangeRecord> records, String type, String field, String value) {
		replace(records, first(records, type), field, value);
	}

	private void replace(List<ExchangeRecord> records, ExchangeRecord record, String field, String value) {
		ObjectNode values = (ObjectNode) record.values();
		values.put(field, value);
		replaceValues(records, record, values);
	}

	private void replaceValues(List<ExchangeRecord> records, ExchangeRecord record, ObjectNode values) {
		records.set(records.indexOf(record), new ExchangeRecord(record.recordVersion(), record.recordType(),
				record.recordId(), record.revisionId(), record.baselineRevisionId(), values));
	}

	private ExchangeRecord node(List<ExchangeRecord> records, String level) {
		String classificationId = first(records, "QUESTION").values().path("classification_node_id").asText();
		String syllabus = records.stream().filter(r -> r.recordId().toString().equals(classificationId)).findFirst()
				.orElseThrow().values().path("syllabus_version_id").asText();
		return records.stream()
				.filter(r -> r.recordType().equals("CURRICULUM_NODE")
						&& r.values().path("curriculum_level").asText().equals(level)
						&& r.values().path("syllabus_version_id").asText().equals(syllabus))
				.findFirst().orElseThrow();
	}

	private ExchangeRecord first(List<ExchangeRecord> records, String type) {
		return records.stream().filter(r -> r.recordType().equals(type)).findFirst().orElseThrow();
	}

	private boolean install(Fixture fixture, Path archive, SubjectImportApprovalPlan plan, boolean assignment)
			throws Exception {
		if (assignment) {
			return new WorkAssignmentInstallationCoordinator(fixture.config()).install(archive).replay();
		}
		return new SubjectImportCoordinator(fixture.config())
				.execute(1, plan, archive, directory.resolve("backups"), "Approve semantic import regression fixture")
				.replay();
	}

	private SubjectImportApprovalPlan approve(Fixture fixture, Path archive) throws Exception {

		// Exercise the coordinator even when a caller supplies decisions from an
		// earlier
		// graph. Fingerprints below bind the actual crafted bytes; semantic validity
		// must not be inferred from approval coverage or a previous preview.
		var preview = new SubjectReconciliationPreviewService().preview(fixture.masterArchive(), fixture.archive());
		var review = new SubjectReconciliationReview(preview);
		for (var key : review.reviewKeys()) {
			review.decide(key, Decision.ACCEPT, "Approved test addition");
		}
		var fingerprints = new SubjectImportSnapshotFingerprint();
		return SubjectImportApprovalPlan.prepare(review, fingerprints.inspect(fixture.masterArchive()),
				fingerprints.inspect(archive));
	}

	private List<String> databaseContents(SqliteDatabase database) throws Exception {
		return databaseContents(database, false);
	}

	private List<String> databaseContents(SqliteDatabase database, boolean replay) throws Exception {
		List<String> contents = new ArrayList<>();
		try (var connection = database.openConnection();
				var tables = connection.createStatement();
				var names = tables.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
			while (names.next()) {
				String table = names.getString(1);
				try (var statement = connection.createStatement();
						var rows = statement.executeQuery("SELECT * FROM \"" + table + "\"")) {
					while (rows.next()) {
						StringBuilder row = new StringBuilder(table);
						for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {

							// A successful replay explicitly reselects its assignment. Only
							// that selection timestamp may change; rejection compares it too.
							if (replay && table.equals("contributor_assignment_selection")
									&& rows.getMetaData().getColumnName(column).equals("updated_at")) {
								continue;
							}
							row.append('|').append(rows.getString(column));
						}
						contents.add(row.toString());
					}
				}
			}
		}
		return contents.stream().sorted().toList();
	}

	private Fixture fixture(boolean assignment, boolean legacy) throws Exception {
		ApplicationConfig source = ApplicationConfig.fromDataRoot(directory.resolve("source"));
		ApplicationConfig target = ApplicationConfig.fromDataRoot(directory.resolve("target-data"));
		Files.createDirectories(source.dataRoot());
		Files.createDirectories(target.dataRoot());
		SqliteDatabase sourceDb = new SqliteDatabase(source.databasePath());
		SqliteDatabase targetDb = new SqliteDatabase(target.databasePath());
		sourceDb.initialiseSchema();
		targetDb.initialiseSchema();
		execute(targetDb, "INSERT INTO subjects(id, subject_name) VALUES (2, 'Chemistry')");
		if (!assignment) {
			execute(targetDb, "INSERT INTO subjects(id, subject_name) VALUES (1, 'Biology')");
		}
		Path unrelated = target.dataRoot().resolve("subjects/Chemistry/keep.txt");
		Files.createDirectories(unrelated.getParent());
		Files.writeString(unrelated, "unrelated");
		Path pdf = source.dataRoot().resolve("subjects/Biology/exams/questions.pdf");
		Files.createDirectories(pdf.getParent());
		Files.write(pdf, TestPdf.bytes("Disposable source asset"));
		execute(sourceDb, "INSERT INTO subjects(id, subject_name) VALUES (1, 'Biology')",
				"INSERT INTO syllabus_versions(id, subject_id, syllabus_name, is_current) VALUES (1, 1, 'Biology 2025', 1), (2, 1, 'Biology 2019', 0)",
				"INSERT INTO curriculum_nodes(id, syllabus_version_id, parent_id, curriculum_code, curriculum_name, curriculum_level, display_order) VALUES "
						+ "(1, " + (legacy ? 2 : 1) + ", NULL, 'U1', 'Unit', 'UNIT', 0)," + "(2, " + (legacy ? 2 : 1)
						+ ", 1, 'T1', 'Topic', 'TOPIC', 0)," + "(3, " + (legacy ? 2 : 1)
						+ ", 2, 'S1', 'Subtopic', 'SUBTOPIC', 0)," + "(4, " + (legacy ? 2 : 1)
						+ ", 3, 'D1', 'Descriptor', 'DESCRIPTOR', 0)," + "(11, " + (legacy ? 1 : 2)
						+ ", NULL, 'U2', 'Other Unit', 'UNIT', 0)," + "(12, " + (legacy ? 1 : 2)
						+ ", 11, 'T2', 'Other Topic', 'TOPIC', 0)," + "(13, " + (legacy ? 1 : 2)
						+ ", 12, 'D2', 'Other Descriptor', 'DESCRIPTOR', 0)",
				"INSERT INTO exam_providers(id, provider_name) VALUES (1, 'QCAA')",
				"INSERT INTO exams(id, subject_id, provider_id, exam_year, exam_name, expected_question_booklet_count, expected_answer_file_count) VALUES (1, 1, 1, 2025, 'External Assessment', 2, 0)",
				"INSERT INTO source_documents(id, relative_path) VALUES (1, 'subjects/Biology/exams/questions.pdf')",
				"INSERT INTO exam_booklets(id, exam_id, source_document_id, booklet_name) VALUES (1, 1, 1, 'Paper 1'), (2, 1, 1, 'Paper 2')",
				"INSERT INTO source_questions(id, booklet_id, source_question_code) VALUES (1, 1, '1')",
				"INSERT INTO shared_question_contexts(id, booklet_id, context_label) VALUES (1, 1, 'Stimulus')",
				"INSERT INTO shared_question_context_regions(shared_context_id, region_order, page_number, x, y, width, height) VALUES (1, 0, 1, 0.1, 0.1, 0.2, 0.2)",
				"INSERT INTO shared_question_context_content_parts(shared_context_id, content_order, content_type, region_order) VALUES (1, 0, 'PDF_REGION', 0)",
				"INSERT INTO questions(id, booklet_id, classification_node_id, question_code, question_text, marks, source_question_id, shared_context_id, shared_context_capture_required) VALUES (1, 1, 4, '1', 'Question', 2, 1, 1, 0)",
				"INSERT INTO question_regions(question_id, region_order, booklet_id, page_number, x, y, width, height) VALUES (1, 0, 1, 1, 0.1, 0.4, 0.5, 0.2)",
				"INSERT INTO question_content_parts(question_id, content_order, content_type, region_order) VALUES (1, 0, 'PDF_REGION', 0)",
				"INSERT INTO answers(id, question_id, answer_text) VALUES (1, 1, 'Answer')");
		Path archive = directory.resolve("original.examwork");
		if (assignment) {
			Exam exam = new Exam(1, new Subject(1, "Biology"), new ExamProvider(1, "QCAA"), 2025,
					"External Assessment");
			new WorkAssignmentExporter(sourceDb, new ManagedDataLayout(source.dataRoot()))
					.export(new WorkAssignmentExporter.Request(exam, UUID.randomUUID(), UUID.randomUUID(),
							"Contributor", true, archive));
		} else {
			new SubjectSnapshotExporter(sourceDb, new ManagedDataLayout(source.dataRoot())).export(1, archive);
		}
		Path master = directory.resolve("master.examwork");
		if (!assignment) {
			new SubjectSnapshotExporter(targetDb, new ManagedDataLayout(target.dataRoot())).export(1, master);
		}
		return new Fixture(target, targetDb, archive, master);
	}

	private void execute(SqliteDatabase database, String... statements) throws Exception {
		try (var connection = database.openConnection(); var statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	private record Fixture(ApplicationConfig config, SqliteDatabase database, Path archive, Path masterArchive) {
	}
}
