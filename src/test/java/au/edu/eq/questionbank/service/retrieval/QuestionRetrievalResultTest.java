package au.edu.eq.questionbank.service.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
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

class QuestionRetrievalResultTest {

	@Test
	void copiesCurrentApplicability() {
		Fixture fixture = new Fixture();
		List<CurriculumNode> applicability = new ArrayList<CurriculumNode>();
		applicability.add(fixture.currentDescriptor);
		QuestionRetrievalResult result = new QuestionRetrievalResult(fixture.question, applicability);
		applicability.clear();
		assertEquals(List.of(fixture.currentDescriptor), result.getCurrentApplicability());
		assertThrows(UnsupportedOperationException.class,
				() -> result.getCurrentApplicability().add(fixture.currentDescriptor));
	}

	@Test
	void exposesOriginalClassificationSeparatelyFromCurrentApplicability() {
		Fixture fixture = new Fixture();
		QuestionRetrievalResult result = new QuestionRetrievalResult(fixture.question,
				List.of(fixture.currentDescriptor));
		assertSame(fixture.question, result.getQuestion());
		assertSame(fixture.historicalDescriptor, result.getOriginalClassification());
		assertEquals(List.of(fixture.currentDescriptor), result.getCurrentApplicability());
		assertSame(fixture.historicalDescriptor, fixture.question.getClassification());
	}

	@Test
	void rejectsEmptyApplicability() {
		Fixture fixture = new Fixture();
		assertThrows(IllegalArgumentException.class, () -> new QuestionRetrievalResult(fixture.question, List.of()));
	}

	@Test
	void rejectsHistoricalApplicability() {
		Fixture fixture = new Fixture();
		assertThrows(IllegalArgumentException.class,
				() -> new QuestionRetrievalResult(fixture.question, List.of(fixture.historicalDescriptor)));
	}

	@Test
	void rejectsNullArguments() {
		Fixture fixture = new Fixture();
		assertThrows(NullPointerException.class,
				() -> new QuestionRetrievalResult(null, List.of(fixture.currentDescriptor)));
		assertThrows(NullPointerException.class, () -> new QuestionRetrievalResult(fixture.question, null));
		List<CurriculumNode> applicability = new ArrayList<CurriculumNode>();
		applicability.add(null);
		assertThrows(NullPointerException.class, () -> new QuestionRetrievalResult(fixture.question, applicability));
	}

	private static final class Fixture {

		private final Descriptor currentDescriptor;
		private final Descriptor historicalDescriptor;
		private final Question question;

		private Fixture() {
			Subject chemistry = new Subject(1, "Chemistry");
			SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
			Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
			Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
			historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
					"Historical descriptor", 1);
			SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
			Unit currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
			Topic currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
			currentDescriptor = new Descriptor(12, currentVersion, currentTopic, "1.1.1", "Current descriptor", 1);
			ExamProvider provider = new ExamProvider(1, "QCAA");
			Exam exam = new Exam(1, chemistry, provider, 2020, "2020 Chemistry");
			SourceDocument sourceDocument = new SourceDocument(1, "paper1.pdf");
			ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
			question = new Question(1, booklet, "3", "", 2, List.of(), historicalDescriptor, false);
		}
	}
}
