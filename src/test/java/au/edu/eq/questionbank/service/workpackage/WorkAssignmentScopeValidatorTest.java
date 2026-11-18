package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.ExamWorkAssetIndex.Reference;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Scope;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.ScopeLevel;

class WorkAssignmentScopeValidatorTest {

	private final WorkAssignmentScopeValidator validator = new WorkAssignmentScopeValidator();

	@Test
	void acceptsCompleteExamWithSharedAnswerFile() throws Exception {
		Fixture fixture = fixture();
		assertDoesNotThrow(
				() -> validator.validate(fixture.manifest(), fixture.records(), fixture.context(), fixture.assets()));
	}

	@Test
	void rejectsAdditionalExamInSameSubject() throws Exception {
		Fixture fixture = fixture();
		List<ExchangeRecord> records = new ArrayList<>(fixture.records());
		records.add(record("EXAM", 90, values("subject_id", uuid(1).toString(), "provider_id", uuid(4).toString())));
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> validator.validate(fixture.manifest(), records, fixture.context(), fixture.assets()));
		assertTrue(failure.getMessage().contains("exactly one EXAM"));
	}

	@Test
	void rejectsAssetOwnedByUnassignedRecord() throws Exception {
		Fixture fixture = fixture();
		List<Reference> references = new ArrayList<>(fixture.assets().references());
		references.add(pdfReference(99, "c".repeat(64), "subjects/Biology/exams/unassigned.pdf"));
		ExamWorkAssetIndex changed = new ExamWorkAssetIndex(references);
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> validator.validate(fixture.manifest(), fixture.records(), fixture.context(), changed));
		assertTrue(failure.getMessage().contains("out-of-scope"));
	}

	@Test
	void rejectsMissingCurrentSyllabus() throws Exception {
		Fixture fixture = fixture();
		List<ExchangeRecord> records = new ArrayList<>(fixture.records());
		records.removeIf(record -> record.recordType().equals("SYLLABUS_VERSION") && record.recordId().equals(uuid(2)));
		ObjectNode oldVersion = values("subject_id", uuid(1).toString(), "syllabus_name", "Biology 2025");
		oldVersion.put("is_current", 0);
		records.add(new ExchangeRecord(1, "SYLLABUS_VERSION", uuid(2), uuid(402), null, oldVersion));
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> validator.validate(fixture.manifest(), records, fixture.context(), fixture.assets()));
		assertTrue(failure.getMessage().contains("current syllabus"));
	}

	@Test
	void rejectsQuestionLinkedToUnassignedBooklet() throws Exception {
		Fixture fixture = fixture();
		List<ExchangeRecord> records = new ArrayList<>(fixture.records());
		records.add(record("QUESTION", 91,
				values("booklet_id", uuid(999).toString(), "classification_node_id", uuid(13).toString())));
		assertThrows(PackageFormatException.class,
				() -> validator.validate(fixture.manifest(), records, fixture.context(), fixture.assets()));
	}

	@Test
	void rejectsUnrelatedSourceDocument() throws Exception {
		Fixture fixture = fixture();
		List<ExchangeRecord> records = new ArrayList<>(fixture.records());
		records.add(record("SOURCE_DOCUMENT", 92, values("relative_path", "subjects/Biology/exams/unassigned.pdf")));
		PackageFormatException failure = assertThrows(PackageFormatException.class,
				() -> validator.validate(fixture.manifest(), records, fixture.context(), fixture.assets()));
		assertTrue(failure.getMessage().contains("source documents"));
	}

	private Fixture fixture() {
		Instant issued = Instant.parse("2026-11-16T00:00:00Z");
		ExamWorkManifest manifest = new ExamWorkManifest(1, uuid(100), ExamWorkPackageType.WORK_ASSIGNMENT, issued,
				uuid(101), uuid(101), uuid(1), "Biology", uuid(102), uuid(103), "1.0.1", "1.0.1", 28, 28,
				new Scope(ScopeLevel.EXAMS, List.of(uuid(3)), List.of()), List.of());
		ObjectNode currentVersion = values("subject_id", uuid(1).toString(), "syllabus_name", "Biology 2025");
		currentVersion.put("is_current", 1);
		ObjectNode legacyVersion = values("subject_id", uuid(1).toString(), "syllabus_name", "Biology 2019");
		legacyVersion.put("is_current", 0);
		ObjectNode exam = values("subject_id", uuid(1).toString(), "provider_id", uuid(4).toString());
		exam.put("expected_question_booklet_count", 2);
		exam.put("expected_answer_file_count", 1);
		List<ExchangeRecord> records = List.of(record("SUBJECT", 1, values("subject_name", "Biology")),
				new ExchangeRecord(1, "SYLLABUS_VERSION", uuid(2), uuid(401), null, currentVersion),
				new ExchangeRecord(1, "SYLLABUS_VERSION", uuid(17), uuid(402), null, legacyVersion),
				record("EXAM_PROVIDER", 4, values("provider_name", "QCAA")),
				record("CURRICULUM_NODE", 13, values("syllabus_version_id", uuid(2).toString())),
				record("EXAM", 3, exam),
				record("EXAM_BOOKLET", 5,
						values("exam_id", uuid(3).toString(), "source_document_id", uuid(8).toString(),
								"answer_file_id", uuid(7).toString())),
				record("EXAM_BOOKLET", 6,
						values("exam_id", uuid(3).toString(), "source_document_id", uuid(8).toString(),
								"answer_file_id", uuid(7).toString())),
				record("ANSWER_FILE", 7,
						values("exam_id", uuid(3).toString(), "source_document_id", uuid(9).toString())),
				record("SOURCE_DOCUMENT", 8, values("relative_path", "subjects/Biology/exams/questions.pdf")),
				record("SOURCE_DOCUMENT", 9, values("relative_path", "subjects/Biology/exams/answers.pdf")),
				record("SOURCE_QUESTION", 14, values("booklet_id", uuid(5).toString())),
				record("SHARED_QUESTION_CONTEXT", 12, sharedContextValues()),
				record("QUESTION", 10,
						values("booklet_id", uuid(5).toString(), "classification_node_id", uuid(13).toString(),
								"source_question_id", uuid(14).toString(), "shared_context_id", uuid(12).toString())),
				record("ANSWER", 11, values("question_id", uuid(10).toString())));
		ObjectNode contextValues = values("assignment_id", manifest.assignmentId().toString(), "baseline_snapshot_id",
				manifest.baselineSnapshotId().toString(), "master_installation_id",
				manifest.originInstallationId().toString(), "subject_id", manifest.subjectId().toString(), "exam_id",
				uuid(3).toString(), "current_syllabus_id", uuid(2).toString(), "legacy_syllabus_id",
				uuid(17).toString(), "issued_at", issued.toString());
		contextValues.put("legacy_capture_enabled", true);
		ExchangeRecord context = new ExchangeRecord(1, "WORK_ASSIGNMENT_CONTEXT", manifest.assignmentId(),
				manifest.baselineSnapshotId(), null, contextValues);
		ExamWorkAssetIndex assets = new ExamWorkAssetIndex(
				List.of(pdfReference(8, "a".repeat(64), "subjects/Biology/exams/questions.pdf"),
						pdfReference(9, "b".repeat(64), "subjects/Biology/exams/answers.pdf")));
		return new Fixture(manifest, records, context, assets);
	}

	private Reference pdfReference(int owner, String hash, String managedPath) {
		return new Reference("assets/sha256/" + hash.substring(0, 2) + "/" + hash + ".pdf", hash, 0, "application/pdf",
				managedPath, "SOURCE_DOCUMENT", uuid(owner), "SOURCE_PDF");
	}

	private ExchangeRecord record(String type, int id, ObjectNode values) {
		return new ExchangeRecord(1, type, uuid(id), uuid(400), null, values);
	}

	private ObjectNode sharedContextValues() {
		ObjectNode values = values("booklet_id", uuid(5).toString());

		// The portable content repository serialises child
		// collections as arrays, including when they are empty.
		values.set("shared_question_context_images", JsonNodeFactory.instance.arrayNode());
		values.set("shared_question_context_regions", JsonNodeFactory.instance.arrayNode());
		values.set("shared_question_context_content_parts", JsonNodeFactory.instance.arrayNode());
		return values;
	}

	private UUID uuid(int value) {
		return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
	}

	private ObjectNode values(String... pairs) {
		if (pairs.length % 2 != 0) {
			throw new IllegalArgumentException("Fields must be supplied as key/value pairs");
		}
		ObjectNode node = JsonNodeFactory.instance.objectNode();
		for (int index = 0; index < pairs.length; index += 2) {
			node.put(pairs[index], pairs[index + 1]);
		}
		return node;
	}

	private record Fixture(ExamWorkManifest manifest, List<ExchangeRecord> records, ExchangeRecord context,
			ExamWorkAssetIndex assets) {
	}
}
