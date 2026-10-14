package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
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

class QuestionSearchInclusionFilterTest {

	private Descriptor first;
	private Descriptor second;
	private Question question;

	@Test
	void allQuestionsScopeCannotUseInclusionRestriction() {
		QuestionSearchResult allQuestions = QuestionSearchResult.allQuestions(question);
		assertTrue(QuestionSearchInclusionFilter.ALL.matches(allQuestions, Set.of()));
		assertThrows(IllegalArgumentException.class,
				() -> QuestionSearchInclusionFilter.EXCLUDED.matches(allQuestions, Set.of()));
		assertThrows(IllegalArgumentException.class,
				() -> QuestionSearchInclusionFilter.INCLUDED.matches(allQuestions, Set.of()));
	}

	@Test
	void excludedMeansAtLeastOneMatchingPlacementExcluded() {
		QuestionSearchResult result = result(first);
		assertFalse(QuestionSearchInclusionFilter.EXCLUDED.matches(result, Set.of()));
		assertTrue(QuestionSearchInclusionFilter.INCLUDED.matches(result, Set.of()));
		assertTrue(QuestionSearchInclusionFilter.EXCLUDED.matches(result, Set.of(first.getId())));
		assertFalse(QuestionSearchInclusionFilter.INCLUDED.matches(result, Set.of(first.getId())));
	}

	@Test
	void exclusionsOutsideSelectedScopeDoNotAffectMatching() {
		QuestionSearchResult firstScope = result(first);
		Set<Long> exclusions = Set.of(second.getId());

		// Although this Question is excluded from another
		// Descriptor, the selected first Descriptor is included.
		assertTrue(QuestionSearchInclusionFilter.INCLUDED.matches(firstScope, exclusions));
		assertFalse(QuestionSearchInclusionFilter.EXCLUDED.matches(firstScope, exclusions));
	}

	@Test
	void filtersPreserveResultOrderAndQuestionIdentity() {
		QuestionSearchResult firstResult = result(first);
		QuestionSearchResult secondResult = result(second);
		List<QuestionSearchResult> results = List.of(firstResult, secondResult);
		Map<Long, Set<Long>> exclusions = Map.of(question.getId(), Set.of(second.getId()));
		assertEquals(List.of(firstResult), QuestionSearchInclusionFilter.INCLUDED.filter(results, exclusions));
		assertEquals(List.of(secondResult), QuestionSearchInclusionFilter.EXCLUDED.filter(results, exclusions));
		assertEquals(results, QuestionSearchInclusionFilter.ALL.filter(results, exclusions));
	}

	@Test
	void includedMeansAtLeastOnePlacementNotExcluded() {
		QuestionSearchResult result = result(first, second);
		Set<Long> exclusions = Set.of(second.getId());
		assertTrue(QuestionSearchInclusionFilter.INCLUDED.matches(result, exclusions));
		assertTrue(QuestionSearchInclusionFilter.EXCLUDED.matches(result, exclusions));

		// Mixed placement states legitimately match both.
		assertTrue(QuestionSearchInclusionFilter.ALL.matches(result, exclusions));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
		Unit unit = new Unit(3, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(4, syllabus, unit, "3.1", "Topic 3.1", 1);
		first = new Descriptor(5, syllabus, topic, "3.1.1", "First Descriptor", 1);
		second = new Descriptor(6, syllabus, topic, "3.1.2", "Second Descriptor", 2);
		Exam exam = new Exam(7, subject, new ExamProvider(8, "QCAA"), 2025, "External Assessment");
		ExamBooklet booklet = new ExamBooklet(9, exam, "Paper 1", new SourceDocument(10, "paper1.pdf"));
		question = new Question(11, booklet, "Q1", "", 1, List.of(), first, false);
	}

	private QuestionSearchResult result(Descriptor... placements) {
		return QuestionSearchResult.currentSyllabus(new QuestionRetrievalResult(question, List.of(placements)));
	}
}
