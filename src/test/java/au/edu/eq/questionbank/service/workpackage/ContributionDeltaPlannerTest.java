package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ContributionDeltaPlannerTest {

	@Test
	void allowsNewWorkToBeCorrectedBeforeOneFinalSubmission() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord originalQuestion = record("QUESTION", 10, 20, """
				{"marks": 1}
				""");
		ExchangeRecord firstDraft = record("QUESTION", 11, 30, """
				{"marks": 2}
				""");
		ExchangeRecord correctedDraft = record("QUESTION", 11, 31, """
				{"marks": 3}
				""");
		var first = planner.plan(List.of(originalQuestion), List.of(originalQuestion, firstDraft));
		var finalPlan = planner.plan(List.of(originalQuestion), List.of(originalQuestion, correctedDraft));
		assertEquals(1, first.candidates().size());
		assertEquals(1, finalPlan.candidates().size());
		assertEquals(firstDraft.recordId(), finalPlan.candidates().getFirst().recordId());
		assertEquals(correctedDraft.revisionId(), finalPlan.candidates().getFirst().revisionId());
		assertEquals(correctedDraft.values(), finalPlan.candidates().getFirst().values());
		assertTrue(finalPlan.originalVersions().isEmpty());
		assertEquals(finalPlan, planner.plan(List.of(originalQuestion), List.of(originalQuestion, correctedDraft)));
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
	void ignoresAutomaticBookletReviewInvalidationButRejectsStructuralChanges() throws Exception {
		ContributionDeltaPlanner planner = new ContributionDeltaPlanner();
		ExchangeRecord original = record("EXAM_BOOKLET", 10, 100, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": [{"reviewed": 1}]
				}
				""");
		ExchangeRecord invalidated = record("EXAM_BOOKLET", 10, 101, """
				{
				  "name": "Paper 1",
				  "booklet_applicability_reviews": []
				}
				""");
		var plan = planner.plan(List.of(original), List.of(invalidated));

		// Capturing a new Question invalidates the local review confirmation.
		// The Booklet itself is not submitted as a contributor modification.
		assertTrue(plan.candidates().isEmpty());
		assertTrue(plan.originalVersions().isEmpty());
		ExchangeRecord changedStructure = record("EXAM_BOOKLET", 10, 102, """
				{
				  "name": "Renamed Paper",
				  "booklet_applicability_reviews": []
				}
				""");
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(original), List.of(changedStructure)));
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

	@Test
	void returnsOnlyNewRecordsInStableOrder() throws Exception {
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
		ExchangeRecord newQuestion = record("QUESTION", 3, 30, """
				{
				  "marks": 3,
				  "classification_node_id": "node-b"
				}
				""");
		ExchangeRecord newAnswer = record("ANSWER", 4, 40, """
				{
				  "question_id": "question-3",
				  "answer_regions": [{"page": 1}]
				}
				""");
		var plan = planner.plan(List.of(originalQuestion, booklet),
				List.of(newQuestion, newAnswer, originalQuestion, booklet));
		assertEquals(List.of("ANSWER", "QUESTION"),
				plan.candidates().stream().map(ExchangeRecord::recordType).toList());
		assertEquals(2, plan.candidates().size());
		assertTrue(plan.candidates().stream().allMatch(record -> record.baselineRevisionId() == null));
		assertTrue(plan.originalVersions().isEmpty());
		ExchangeRecord alteredBaseline = record("QUESTION", 2, 21, """
				{
				  "marks": 5,
				  "classification_node_id": "node-a"
				}
				""");
		assertThrows(PackageFormatException.class, () -> planner.plan(List.of(originalQuestion, booklet),
				List.of(newQuestion, newAnswer, alteredBaseline, booklet)));
	}

	private ExchangeRecord record(String type, long id, long revision, String json) throws JsonProcessingException {
		return new ExchangeRecord(ExchangeRecord.CURRENT_RECORD_VERSION, type, new UUID(0L, id), new UUID(1L, revision),
				null, new ObjectMapper().readTree(json));
	}
}
