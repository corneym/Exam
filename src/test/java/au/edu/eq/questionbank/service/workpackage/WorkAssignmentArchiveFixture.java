package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.ApplicationVersion;
import au.edu.eq.questionbank.TestPdf;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.ExamWorkArchiveWriter.EntrySource;
import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

final class WorkAssignmentArchiveFixture {

	static final String QUESTION_PATH = "subjects/Biology/exams/QCAA/2025/External Assessment/questions.pdf";
	static final String ANSWER_PATH = "subjects/Biology/exams/QCAA/2025/External Assessment/answers.pdf";
	static final byte[] QUESTION_BYTES = pdf("Question fixture");
	static final byte[] ANSWER_BYTES = pdf("Answer fixture");

	private WorkAssignmentArchiveFixture() {
	}

	static ObjectNode fields(String... entries) {
		if (entries.length % 2 != 0) {
			throw new IllegalArgumentException("Fields must be supplied as key/value pairs");
		}
		ObjectNode node = JsonNodeFactory.instance.objectNode();

		// Check the bound for both array elements before accessing
		// a key/value pair.
		for (int i = 0; i + 1 < entries.length; i += 2) {
			node.put(entries[i], entries[i + 1]);
		}
		return node;
	}

	static Path write(Path destination) throws Exception {
		Instant issued = Instant.parse("2026-11-16T00:00:00Z");
		ObjectNode current = fields("subject_id", id(1).toString(), "syllabus_name", "Biology 2025");
		current.put("is_current", 1);
		current.put("curriculum_status", "IN_PROGRESS");
		current.putNull("curriculum_finalised_at");
		ObjectNode unit = fields("syllabus_version_id", id(2).toString(), "curriculum_level", "UNIT", "curriculum_code",
				"U1", "curriculum_name", "Biology Unit");
		unit.putNull("parent_id");
		unit.put("display_order", 0);
		unit.putNull("source_page_number");
		ObjectNode topic = curriculumNode(13, "TOPIC", "U1.T1", "Biology Topic");
		ObjectNode subtopic = curriculumNode(14, "SUBTOPIC", "U1.T1.S1", "Biology Subtopic");
		ObjectNode exam = fields("subject_id", id(1).toString(), "provider_id", id(4).toString(), "exam_name",
				"External Assessment");
		exam.put("exam_year", 2025);
		exam.put("capture_state", "ACTIVE");
		exam.put("expected_question_booklet_count", 2);
		exam.put("expected_answer_file_count", 1);
		ObjectNode paper1 = bookletValues("Paper 1");
		ObjectNode paper2 = bookletValues("Paper 2");
		ObjectNode answers = fields("exam_id", id(3).toString(), "source_document_id", id(9).toString(),
				"answer_file_name", "Marking guide");
		answers.put("contains_answer_explanations", 0);
		ObjectNode question = fields("booklet_id", id(5).toString(), "classification_node_id", id(15).toString(),
				"question_code", "1", "question_text", "Assigned question", "response_type", "WRITTEN_RESPONSE");
		question.put("marks", 1);
		question.put("shared_context_capture_required", 0);
		question.putNull("source_question_id");
		question.putNull("shared_context_id");
		question.put("source_capture_required", 0);
		List<ExchangeRecord> records = List.of(record("SUBJECT", 1, fields("subject_name", "Biology")),
				record("SYLLABUS_VERSION", 2, current), record("CURRICULUM_NODE", 13, unit),
				record("CURRICULUM_NODE", 14, topic), record("CURRICULUM_NODE", 15, subtopic),
				record("EXAM_PROVIDER", 4, fields("provider_name", "QCAA")), record("EXAM", 3, exam),
				record("EXAM_BOOKLET", 5, paper1), record("EXAM_BOOKLET", 6, paper2), record("ANSWER_FILE", 7, answers),
				record("QUESTION", 10, question),
				record("SOURCE_DOCUMENT", 8, fields("content_sha256", hash(QUESTION_BYTES))),
				record("SOURCE_DOCUMENT", 9, fields("content_sha256", hash(ANSWER_BYTES))));
		ObjectNode contextValues = fields("assignment_id", id(102).toString(), "baseline_snapshot_id",
				id(103).toString(), "master_installation_id", id(101).toString(), "contributor_id", id(104).toString(),
				"contributor_name", "Test Contributor", "subject_id", id(1).toString(), "exam_id", id(3).toString(),
				"current_syllabus_id", id(2).toString(), "issued_at", issued.toString());
		contextValues.put("legacy_capture_enabled", false);
		ExchangeRecord context = new ExchangeRecord(1, "WORK_ASSIGNMENT_CONTEXT", id(102), id(103), null,
				contextValues);
		ExamWorkAssetIndex index = new ExamWorkAssetIndex(
				List.of(reference(8, QUESTION_PATH, QUESTION_BYTES), reference(9, ANSWER_PATH, ANSWER_BYTES)));
		ExamWorkJsonlCodec codec = new ExamWorkJsonlCodec();
		Map<String, byte[]> contents = new LinkedHashMap<>();
		ByteArrayOutputStream recordBytes = new ByteArrayOutputStream();
		codec.writeRecords(records, recordBytes);
		contents.put("records/subject-graph.jsonl", recordBytes.toByteArray());
		contents.put("baseline/initial-records.jsonl", recordBytes.toByteArray());
		ByteArrayOutputStream contextBytes = new ByteArrayOutputStream();
		codec.writeRecords(List.of(context), contextBytes);
		contents.put("records/assignment-context.jsonl", contextBytes.toByteArray());
		ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
		codec.writeAssetIndex(index, indexBytes);
		contents.put(ExamWorkAssetIndex.INDEX_PATH, indexBytes.toByteArray());
		contents.put(assetPath(QUESTION_BYTES), QUESTION_BYTES);
		contents.put(assetPath(ANSWER_BYTES), ANSWER_BYTES);
		List<Entry> entries = new ArrayList<>();
		Map<String, EntrySource> sources = new LinkedHashMap<>();
		for (var item : contents.entrySet()) {
			String path = item.getKey();
			byte[] bytes = item.getValue();
			EntryRole role;
			if (path.startsWith("assets/")) {
				role = EntryRole.ASSET;
			} else if (path.startsWith("baseline/")) {
				role = EntryRole.BASELINE;
			} else if (path.equals(ExamWorkAssetIndex.INDEX_PATH)) {
				role = EntryRole.INDEX;
			} else {
				role = EntryRole.RECORDS;
			}
			entries.add(new Entry(path, role, bytes.length, hash(bytes)));
			sources.put(path, () -> new ByteArrayInputStream(bytes));
		}
		int schema = SqliteDatabase.latestSchemaVersion();
		String version = ApplicationVersion.current();
		ExamWorkManifest manifest = new ExamWorkManifest(1, id(100), ExamWorkPackageType.WORK_ASSIGNMENT, issued,
				id(101), id(101), id(1), "Biology", id(102), id(103), version, version, schema, schema,
				new Scope(ScopeLevel.EXAMS, List.of(id(3)), List.of()), entries);
		try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE)) {
			new ExamWorkArchiveWriter().write(manifest, sources, output);
		}
		return destination;
	}

	private static byte[] pdf(String title) {
		try {
			return TestPdf.bytes(title);
		} catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
	}

	private static String assetPath(byte[] bytes) throws Exception {
		String digest = hash(bytes);
		return "assets/sha256/" + digest.substring(0, 2) + "/" + digest + ".pdf";
	}

	private static ObjectNode bookletValues(String name) {
		ObjectNode values = fields("exam_id", id(3).toString(), "source_document_id", id(8).toString(),
				"answer_file_id", id(7).toString(), "booklet_name", name, "question_format", "UNSPECIFIED");
		values.putNull("pending_mcq_shared_context_id");
		values.putNull("expected_question_count");
		values.putNull("legacy_paper_code");
		return values;
	}

	private static ObjectNode curriculumNode(int parentId, String level, String code, String name) {
		ObjectNode values = fields("syllabus_version_id", id(2).toString(), "parent_id", id(parentId).toString(),
				"curriculum_level", level, "curriculum_code", code, "curriculum_name", name);
		values.put("display_order", 0);
		values.putNull("source_page_number");
		return values;
	}

	private static String hash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static UUID id(int value) {
		return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
	}

	private static ExchangeRecord record(String type, int id, ObjectNode values) {
		switch (type) {
		case "CURRICULUM_NODE" -> values.putArray("curriculum_mapping_reviews");
		case "EXAM_BOOKLET" -> values.putArray("booklet_applicability_reviews");
		case "QUESTION" -> {
			values.putArray("question_regions");
			values.putArray("question_images");
			values.putArray("question_content_parts");
			values.putArray("question_output_exclusions");
		}
		case "ANSWER" -> values.putArray("answer_regions");
		case "SHARED_QUESTION_CONTEXT" -> {
			values.putArray("shared_question_context_regions");
			values.putArray("shared_question_context_images");
			values.putArray("shared_question_context_content_parts");
		}
		default -> {

			// Entity types without owned child collections need no fixture fields.
		}
		}
		return new ExchangeRecord(1, type, id(id), id(id + 200), null, values);
	}

	private static Reference reference(int owner, String path, byte[] bytes) throws Exception {
		return new Reference(assetPath(bytes), hash(bytes), bytes.length, "application/pdf", path, "SOURCE_DOCUMENT",
				id(owner), "SOURCE_PDF");
	}
}
