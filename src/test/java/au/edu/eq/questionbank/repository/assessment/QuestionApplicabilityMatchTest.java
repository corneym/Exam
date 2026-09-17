package au.edu.eq.questionbank.repository.assessment;

import static org.junit.jupiter.api.Assertions.assertSame;
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

class QuestionApplicabilityMatchTest {

	@Test
	void rejectsCurrentNodeFromDifferentSubject() {
		Subject chemistry = new Subject(1, "Chemistry");
		Subject physics = new Subject(2, "Physics");
		SyllabusVersion historicalChemistry = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalChemistry, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalChemistry, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor historicalDescriptor = new Descriptor(3, historicalChemistry, historicalTopic, "1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion currentPhysics = new SyllabusVersion(2, physics, "2025", true);
		Unit currentUnit = new Unit(10, currentPhysics, "1", "Physics unit", 1);
		Topic currentTopic = new Topic(11, currentPhysics, currentUnit, "1.1", "Physics topic", 1);
		Descriptor currentDescriptor = new Descriptor(12, currentPhysics, currentTopic, "1.1.1", "Physics descriptor",
				1);
		Question question = createQuestion(chemistry, historicalDescriptor);
		assertThrows(IllegalArgumentException.class, () -> new QuestionApplicabilityMatch(question, currentDescriptor));
	}

	@Test
	void rejectsHistoricalCurrentNode() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit unit = new Unit(1, historicalVersion, "1", "Unit", 1);
		Topic topic = new Topic(2, historicalVersion, unit, "1.1", "Topic", 1);
		Descriptor descriptor = new Descriptor(3, historicalVersion, topic, "1.1.1", "Descriptor", 1);
		Question question = createQuestion(chemistry, descriptor);
		assertThrows(IllegalArgumentException.class, () -> new QuestionApplicabilityMatch(question, descriptor));
	}

	@Test
	void rejectsNullArguments() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion currentVersion = new SyllabusVersion(1, chemistry, "2025", true);
		Unit unit = new Unit(1, currentVersion, "1", "Unit", 1);
		Topic topic = new Topic(2, currentVersion, unit, "1.1", "Topic", 1);
		Descriptor descriptor = new Descriptor(3, currentVersion, topic, "1.1.1", "Descriptor", 1);
		Question question = createQuestion(chemistry, descriptor);
		assertThrows(NullPointerException.class, () -> new QuestionApplicabilityMatch(null, descriptor));
		assertThrows(NullPointerException.class, () -> new QuestionApplicabilityMatch(question, null));
	}

	@Test
	void rejectsUnsupportedCurrentLevel() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
		Topic currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
		Question question = createQuestion(chemistry, historicalDescriptor);
		assertThrows(IllegalArgumentException.class, () -> new QuestionApplicabilityMatch(question, currentTopic));
	}

	@Test
	void storesQuestionAndCurrentNode() {
		Subject chemistry = new Subject(1, "Chemistry");
		SyllabusVersion historicalVersion = new SyllabusVersion(1, chemistry, "2019", false);
		Unit historicalUnit = new Unit(1, historicalVersion, "1", "Historical unit", 1);
		Topic historicalTopic = new Topic(2, historicalVersion, historicalUnit, "1.1", "Historical topic", 1);
		Descriptor historicalDescriptor = new Descriptor(3, historicalVersion, historicalTopic, "1.1.1",
				"Historical descriptor", 1);
		SyllabusVersion currentVersion = new SyllabusVersion(2, chemistry, "2025", true);
		Unit currentUnit = new Unit(10, currentVersion, "1", "Current unit", 1);
		Topic currentTopic = new Topic(11, currentVersion, currentUnit, "1.1", "Current topic", 1);
		Descriptor currentDescriptor = new Descriptor(12, currentVersion, currentTopic, "1.1.1", "Current descriptor",
				1);
		Question question = createQuestion(chemistry, historicalDescriptor);
		QuestionApplicabilityMatch match = new QuestionApplicabilityMatch(question, currentDescriptor);
		assertSame(question, match.getQuestion());
		assertSame(currentDescriptor, match.getCurrentNode());
	}

	private Question createQuestion(Subject subject, Descriptor classification) {
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2020, "2020 exam");
		SourceDocument sourceDocument = new SourceDocument(1, "exam.pdf");
		ExamBooklet booklet = new ExamBooklet(1, exam, "Paper 1", sourceDocument);
		return new Question(1, booklet, "1", "", 2, List.of(), classification, false);
	}
}
