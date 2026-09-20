package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.retrieval.QuestionRetrievalResult;

class QuestionSearchResultTest {

	@Test
	void allQuestionsUsesSourceOrderWithoutClaimingApplicability() {
		Fixture fixture = new Fixture();
		Question question10 = fixture.question(1, "10");
		Question question3 = fixture.question(2, "3");

		// Deliberately provide persistence/input order opposite to natural Question
		// order so the Search adapter must impose source order itself.
		List<QuestionSearchResult> results = QuestionSearchResult.allQuestionResults(List.of(question10, question3));
		assertEquals(List.of("3", "10"), results.stream().map(result -> result.question().getQuestionCode()).toList());
		assertEquals(QuestionSearchScope.ALL_QUESTIONS, results.getFirst().scope());
		assertEquals(List.of(), results.getFirst().currentApplicability());
	}

	@Test
	void currentSyllabusPreservesRetrievalApplicabilityAndUsesSourceOrder() {
		Fixture fixture = new Fixture();
		Question question10 = fixture.question(1, "10");
		Question question3 = fixture.question(2, "3");
		QuestionRetrievalResult result10 = new QuestionRetrievalResult(question10, List.of(fixture.currentDescriptor));
		QuestionRetrievalResult result3 = new QuestionRetrievalResult(question3, List.of(fixture.currentDescriptor));

		// Search presentation may reorder results, but it must preserve the retrieval
		// service's authoritative applicability snapshot for each Question.
		List<QuestionSearchResult> results = QuestionSearchResult.currentSyllabusResults(List.of(result10, result3));
		assertEquals(List.of("3", "10"), results.stream().map(result -> result.question().getQuestionCode()).toList());
		assertEquals(QuestionSearchScope.CURRENT_SYLLABUS, results.getFirst().scope());
		assertEquals(List.of(fixture.currentDescriptor), results.getFirst().currentApplicability());
	}

	@Test
	void scopeInvariantsRejectMisrepresentedApplicability() {
		Fixture fixture = new Fixture();

		// The public factories are deliberately asymmetric: callers cannot manufacture
		// an All Questions result that pretends curriculum applicability was evaluated.
		assertThrows(NullPointerException.class, () -> QuestionSearchResult.allQuestions(null));
		assertThrows(NullPointerException.class, () -> QuestionSearchResult.currentSyllabus(null));
	}

	private static final class Fixture {

		private final Subject subject = new Subject(1, "Chemistry");
		private final SyllabusVersion currentSyllabus = new SyllabusVersion(2, subject, "2025", true);
		private final Unit currentUnit = new Unit(3, currentSyllabus, "1", "Unit 1", 1);
		private final Topic currentTopic = new Topic(4, currentSyllabus, currentUnit, "1.1", "Topic 1", 1);
		private final Descriptor currentDescriptor = new Descriptor(5, currentSyllabus, currentTopic, "1.1.1",
				"Descriptor", 1);
		private final ExamProvider provider = new ExamProvider(6, "QCAA");
		private final Exam exam = new Exam(7, subject, provider, 2025, "External Assessment");
		private final ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1",
				new SourceDocument(9, "Chemistry/2025/paper1.pdf"));

		private Question question(long id, String code) {

			// Only identity, source metadata and classification matter for Search
			// ordering in this fixture; regions are deliberately unnecessary.
			return new Question(id, booklet, code, "", 1, List.of(), currentDescriptor, false);
		}
	}
}
