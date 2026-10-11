package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamBookletQuestionFormat;
import au.edu.eq.questionbank.model.ExamCaptureState;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;

class ExamApplicabilityReviewTest {

	private final Subject subject = new Subject(1, "Chemistry");
	private final ExamProvider provider = new ExamProvider(2, "QCAA");
	private final Exam exam = new Exam(3, subject, provider, 2025, "External Assessment", ExamCaptureState.ACTIVE);
	private final ExamBooklet paper1 = booklet(10, "Paper 1");
	private final ExamBooklet paper2 = booklet(20, "Paper 2");

	@Test
	void incompleteOrUnclassifiedBookletCannotBeAttested() {
		BookletCorpusStatus incomplete = new BookletCorpusStatus(paper1, true, null, 1, 1, 0,
				new QuestionCorpusSummary(1, 0, 1, 1, 0, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		BookletCorpusStatus unclassified = new BookletCorpusStatus(paper2, true, null, 1, 1, 1,
				new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0), new McqExplanationCoverage(false, 0, 0), Set.of());
		ExamAssetExpectations counts = new ExamAssetExpectations(2, 2, 0, 0);

		// Even an existing review flag must not override known
		// outstanding capture or classification requirements.
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(counts, List.of(incomplete, ready(paper2)),
				Set.of(paper1.getId(), paper2.getId())));
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(counts, List.of(ready(paper1), unclassified),
				Set.of(paper1.getId(), paper2.getId())));
	}

	@Test
	void requiresExplicitReviewForEveryExpectedBooklet() {
		BookletCorpusStatus first = ready(paper1);
		BookletCorpusStatus second = ready(paper2);
		ExamAssetExpectations counts = new ExamAssetExpectations(2, 2, 0, 0);
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(counts, List.of(first, second), Set.of()));
		assertFalse(
				ExamApplicabilityReview.allBookletsReviewed(counts, List.of(first, second), Set.of(paper1.getId())));
		assertTrue(ExamApplicabilityReview.allBookletsReviewed(counts, List.of(first, second),
				Set.of(paper1.getId(), paper2.getId())));
	}

	@Test
	void unknownMissingAndExtraBookletsCannotBeReviewed() {
		BookletCorpusStatus first = ready(paper1);
		BookletCorpusStatus second = ready(paper2);
		Set<Long> reviewed = Set.of(paper1.getId(), paper2.getId());

		// Unknown expected count cannot imply completion.
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(new ExamAssetExpectations(null, 2, 0, 0),
				List.of(first, second), reviewed));

		// One of two expected booklets is missing.
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(new ExamAssetExpectations(2, 1, 0, 0), List.of(first),
				reviewed));

		// Two booklets exist but only one was expected.
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(new ExamAssetExpectations(1, 2, 0, 0),
				List.of(first, second), reviewed));

		// No eligible booklet must not be treated as reviewed.
		assertFalse(ExamApplicabilityReview.allBookletsReviewed(new ExamAssetExpectations(null, 0, 0, 0), List.of(),
				Set.of()));
	}

	private ExamBooklet booklet(long id, String name) {
		return new ExamBooklet(id, exam, name, new SourceDocument(id + 1, "Chemistry/2025/" + id + ".pdf"),
				ExamBookletQuestionFormat.WRITTEN_RESPONSE, 1);
	}

	private BookletCorpusStatus ready(ExamBooklet booklet) {
		return new BookletCorpusStatus(booklet, true, null, 1, 1, 0, new QuestionCorpusSummary(1, 1, 0, 0, 0, 0, 0),
				new McqExplanationCoverage(false, 0, 0), Set.of());
	}
}
