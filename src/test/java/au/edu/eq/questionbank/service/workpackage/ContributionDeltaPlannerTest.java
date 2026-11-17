package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ContributionDeltaPlannerTest {

	@Test
	void allowsBookletApplicabilityReviewWithoutPermittingOtherBookletChanges() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord original = record("EXAM_BOOKLET", 10, 100, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": []
				}
				""");
		ExchangeRecord reviewed = record("EXAM_BOOKLET", 10, 101, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": [
				    {"reviewed": 1}
				  ]
				}
				""");
		var plan = planner.plan(List.of(original), List.of(reviewed));
		assertEquals(1, plan.candidates().size());
		assertEquals(original.revisionId(), plan.candidates().getFirst().baselineRevisionId());
		assertEquals(List.of(original), plan.originalVersions());
		ExchangeRecord changedStructure = record("EXAM_BOOKLET", 10, 102, """
				{
				  "name": "Renamed Paper",
				  "booklet_applicability_reviews": [
				    {"reviewed": 1}
				  ]
				}
				""");
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(changedStructure)));
		ExchangeRecord newExam = record("EXAM", 20, 103, """
				{"year": 2026}
				""");
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(original, newExam)));
	}

	@Test
	void detectsNewChangedAndUnchangedRecordsInStableOrder() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord booklet = record("EXAM_BOOKLET", 1, 10, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": []
				}
				""");
		ExchangeRecord originalQuestion = record("QUESTION", 2, 20, """
				{
				  "marks": 2,
				  "classification_node_id": "node-a"
				}
				""");
		ExchangeRecord changedQuestion = record("QUESTION", 2, 21, """
				{
				  "marks": 2,
				  "classification_node_id": "node-b"
				}
				""");
		ExchangeRecord newAnswer = record("ANSWER", 3, 30, """
				{
				  "question_id": "question-2",
				  "answer_regions": [{"page": 1}]
				}
				""");
		var plan = planner.plan(List.of(originalQuestion, booklet), List.of(changedQuestion, newAnswer, booklet));
		assertEquals(2, plan.candidates().size());

		// Results must be ordered by record type and UUID.
		assertEquals(List.of("ANSWER", "QUESTION"),
				plan.candidates().stream().map(ExchangeRecord::recordType).toList());
		ExchangeRecord answerCandidate = plan.candidates().get(0);
		ExchangeRecord questionCandidate = plan.candidates().get(1);
		assertNull(answerCandidate.baselineRevisionId());
		assertEquals(originalQuestion.revisionId(), questionCandidate.baselineRevisionId());
		assertEquals(changedQuestion.revisionId(), questionCandidate.revisionId());
		assertEquals(changedQuestion.values(), questionCandidate.values());
		assertEquals(List.of(originalQuestion), plan.originalVersions());

		// The original input records remain unchanged.
		assertNull(originalQuestion.baselineRevisionId());
		assertEquals(20L, originalQuestion.revisionId().getLeastSignificantBits());
	}

	@Test
	void doesNotExportRevisionOnlyChanges() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord original = record("QUESTION", 1, 10, """
				{
				  "marks": 3,
				  "question_regions": [
				    {"page": 1, "x": 10}
				  ]
				}
				""");

		// The contributor changed and then restored the values.
		// The revision differs, but the semantic content is identical.
		ExchangeRecord restored = record("QUESTION", 1, 11, """
				{
				  "question_regions": [
				    {"x": 10, "page": 1}
				  ],
				  "marks": 3
				}
				""");
		var plan = planner.plan(List.of(original), List.of(restored));
		assertTrue(plan.candidates().isEmpty());
		assertTrue(plan.originalVersions().isEmpty());
	}

	@Test
	void preservesCandidatesAcrossFourPartialReturns() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord booklet = record("EXAM_BOOKLET", 1, 10, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": []
				}
				""");
		ExchangeRecord originalQuestion = record("QUESTION", 10, 20, """
				{"marks": 1}
				""");
		List<ExchangeRecord> baseline = List.of(booklet, originalQuestion);

		// Return 1: one newly captured Question.
		ExchangeRecord newQuestion = record("QUESTION", 11, 30, """
				{"marks": 2}
				""");
		List<ExchangeRecord> first = List.of(booklet, originalQuestion, newQuestion);

		// Return 2: an Answer is captured.
		ExchangeRecord newAnswer = record("ANSWER", 20, 40, """
				{"question_id": "question-11"}
				""");
		List<ExchangeRecord> second = List.of(booklet, originalQuestion, newQuestion, newAnswer);

		// Return 3: a pre-existing Question is reclassified.
		ExchangeRecord changedOriginal = record("QUESTION", 10, 21, """
				{"marks": 1, "classification": "B"}
				""");
		List<ExchangeRecord> third = List.of(booklet, changedOriginal, newQuestion, newAnswer);

		// Return 4: additional Shared Context is captured.
		ExchangeRecord newContext = record("SHARED_QUESTION_CONTEXT", 30, 50, """
				{"booklet_id": "booklet-1"}
				""");
		List<ExchangeRecord> fourth = List.of(booklet, changedOriginal, newQuestion, newAnswer, newContext);
		List<List<ExchangeRecord>> returns = List.of(first, second, third, fourth);
		for (int i = 0; i < returns.size(); i++) {
			var plan = planner.plan(baseline, returns.get(i));
			assertEquals(i + 1, plan.candidates().size(), "Unexpected candidate count for return " + (i + 1));

			// Prior work remains in subsequent exports while
			// no acknowledgement has been received.
			assertTrue(plan.candidates().stream().anyMatch(candidate -> candidate.recordType().equals("QUESTION")
					&& candidate.recordId().equals(newQuestion.recordId())));
		}
		var finalPlan = planner.plan(baseline, fourth);
		assertEquals(List.of(originalQuestion), finalPlan.originalVersions());

		// Repeating the same snapshot is deterministic.
		assertEquals(finalPlan, planner.plan(baseline, fourth));

		// Planning does not advance or alter the issued baseline.
		assertEquals(List.of(booklet, originalQuestion), baseline);
	}

	@Test
	void rejectsDuplicateMissingAndUnknownRecords() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord original = record("QUESTION", 1, 10, """
				{"marks": 2}
				""");

		// Duplicate portable identities cannot be returned.
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(original, original)));

		// Removal of an issued baseline record is not
		// an authorised contribution deletion.
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of()));
		ExchangeRecord unknown = record("UNKNOWN_ENTITY", 2, 20, """
				{"value": 1}
				""");
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(original, unknown)));
	}

	@Test
	void rejectsOldRevisionOnChangedValues() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord original = record("QUESTION", 1, 10, """
				{"marks": 2}
				""");
		ExchangeRecord changedWithoutRevision = record("QUESTION", 1, 10, """
				{"marks": 5}
				""");
		assertThrows(PackageFormatException.class,
				() -> planner.plan(List.of(original), List.of(changedWithoutRevision)));
		ExchangeRecord missingRevision = new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, "QUESTION",
				original.recordId(), null, null, original.values());
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(missingRevision)));
	}

	private ExchangeRecord record(String type, long id, long revision, String json) throws JsonProcessingException {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, new UUID(0L, id), new UUID(1L, revision),
				null, new ObjectMapper().readTree(json));
	}
}
