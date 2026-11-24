package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.edu.eq.questionbank.service.workpackage.ExchangeRecord;
import au.edu.eq.questionbank.service.workpackage.MasterContributionDecisionLedger;
import au.edu.eq.questionbank.service.workpackage.MasterContributionIntake;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.RecordAssessment;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewPlan;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

class MasterContributionReviewDialogTest {

	@Test
	void completeContributionMustBeEligibleForMerge() {
		RecordAssessment question = assessment("QUESTION", ReviewStatus.READY_NEW);
		RecordAssessment answer = assessment("ANSWER", ReviewStatus.READY_NEW);
		var permitted = MasterContributionDecisionLedger.mergeSelections(plan(List.of(question, answer)));
		assertEquals(2, permitted.size());
		assertTrue(permitted.values().stream()
				.allMatch(choice -> choice == MasterContributionDecisionLedger.Choice.QUEUE_APPLY));

		// One invalid proposal must prevent approval
		// of the entire contribution.
		for (ReviewStatus status : List.of(ReviewStatus.READY_UPDATE, ReviewStatus.SKIP_IDENTICAL,
				ReviewStatus.HELD_STALE_MASTER, ReviewStatus.HELD_MISSING_MASTER, ReviewStatus.HELD_ID_COLLISION,
				ReviewStatus.HELD_OUT_OF_SCOPE, ReviewStatus.HELD_LINEAGE)) {
			RecordAssessment invalid = assessment("QUESTION", status);
			assertThrows(IllegalArgumentException.class,
					() -> MasterContributionDecisionLedger.mergeSelections(plan(List.of(question, invalid))));
		}
		RecordAssessment unsupported = assessment("SYLLABUS_VERSION", ReviewStatus.READY_NEW);
		assertThrows(IllegalArgumentException.class,
				() -> MasterContributionDecisionLedger.mergeSelections(plan(List.of(question, unsupported))));
		assertThrows(IllegalArgumentException.class,
				() -> MasterContributionDecisionLedger.mergeSelections(plan(List.of())));
	}

	@Test
	void previewUsesActualBookletNamesAndQuestionCodes() {
		UUID subjectId = UUID.randomUUID();
		UUID providerId = UUID.randomUUID();
		UUID examId = UUID.randomUUID();
		UUID bookletId = UUID.randomUUID();
		UUID questionId = UUID.randomUUID();
		List<ExchangeRecord> baseline = List.of(record("SUBJECT", subjectId, Map.of("subject_name", "Chemistry")),
				record("EXAM_PROVIDER", providerId, Map.of("provider_name", "QCAA")),
				record("EXAM", examId,
						Map.of("subject_id", subjectId.toString(), "provider_id", providerId.toString(), "exam_year",
								2025, "exam_name", "External Examination")),
				record("EXAM_BOOKLET", bookletId, Map.of("exam_id", examId.toString(), "booklet_name", "Paper 1")));
		List<ExchangeRecord> proposals = List.of(
				record("QUESTION", questionId,
						Map.of("booklet_id", bookletId.toString(), "question_code", "12", "response_type",
								"MULTIPLE_CHOICE")),
				record("ANSWER", UUID.randomUUID(),
						Map.of("question_id", questionId.toString(), "answer_text", "C", "answer_regions",
								List.of(Map.of("region_order", 1)))),
				record("QUESTION_IMAGE", UUID.randomUUID(), Map.of("question_id", questionId.toString())),
				record("SOURCE_QUESTION", UUID.randomUUID(),
						Map.of("booklet_id", bookletId.toString(), "source_question_code", "12")),
				record("SHARED_QUESTION_CONTEXT", UUID.randomUUID(), Map.of("booklet_id", bookletId.toString())));
		var preview = MasterContributionReviewDialog.buildPreview(baseline, proposals, examId);
		assertEquals("Chemistry", preview.subjectName());
		assertEquals("QCAA 2025 External Examination", preview.examName());
		assertEquals(1, preview.booklets().size());
		var summary = preview.booklets().getFirst();
		assertEquals("Paper 1", summary.booklet());
		assertEquals(1L, summary.questions());
		assertEquals(1L, summary.answers());
		assertEquals(1L, summary.mcqExplanations());
		assertEquals(1L, summary.sharedContexts());

		// Supporting records must not inflate Question counts.
		assertEquals(5, preview.items().size());
		assertTrue(preview.items().stream().anyMatch(
				item -> item.item().equals("Question 12") && item.description().equals("Answer and MCQ Explanation")));
		assertTrue(preview.items().stream().allMatch(item -> item.booklet().equals("Paper 1")));
	}

	private RecordAssessment assessment(String type, ReviewStatus status) {
		boolean hasMaster = status == ReviewStatus.READY_UPDATE || status == ReviewStatus.SKIP_IDENTICAL
				|| status == ReviewStatus.HELD_STALE_MASTER;
		return new RecordAssessment(type, UUID.randomUUID(), null, UUID.randomUUID(),
				hasMaster ? UUID.randomUUID() : null, hasMaster ? "0".repeat(64) : null, status);
	}

	private ReviewPlan plan(List<RecordAssessment> assessments) {
		return new ReviewPlan(UUID.randomUUID(), UUID.randomUUID(),
				MasterContributionIntake.IntakeStatus.READY_FOR_REVIEW, Instant.now(), assessments);
	}

	private ExchangeRecord record(String type, UUID id, Map<String, ?> values) {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, id, UUID.randomUUID(), null,
				new ObjectMapper().valueToTree(values));
	}
}
