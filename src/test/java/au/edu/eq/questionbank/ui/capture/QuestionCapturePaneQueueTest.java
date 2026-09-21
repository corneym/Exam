package au.edu.eq.questionbank.ui.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionCapturePaneQueueTest {

	@Test
	void workingSubjectExcludesImportedQuestionsFromOtherSubjects() {
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Subject chemistry = new Subject(2, "Chemistry");
		Question chemistryQuestion = question(10, chemistry, provider, "Chemistry/2024/paper.pdf", "1");
		Subject engineering = new Subject(20, "Engineering");
		Question engineeringQuestion = question(30, engineering, provider, "Engineering/2024/paper.pdf", "2");

		// Working Subject changes only which uncaptured Questions are presented to
		// the user. Questions belonging to other Subjects remain stored unchanged.
		List<Question> result = QuestionCapturePane
				.awaitingCaptureForWorkingSubject(List.of(engineeringQuestion, chemistryQuestion), chemistry);
		assertEquals(List.of(chemistryQuestion), result);
	}

	private Question question(long id, Subject subject, ExamProvider provider, String sourcePath, String questionCode) {
		SyllabusVersion syllabus = new SyllabusVersion(id + 1, subject, "2025", true);
		Unit unit = new Unit(id + 2, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(id + 3, syllabus, unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = new Descriptor(id + 4, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		Exam exam = new Exam(id + 5, subject, provider, 2024, "External Assessment");
		ExamBooklet booklet = new ExamBooklet(id + 6, exam, "Paper 1", new SourceDocument(id + 7, sourcePath));

		// An empty region list represents an imported Question still awaiting
		// Question-region capture.
		return new Question(id, booklet, questionCode, "", 1, List.of(), descriptor, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
	}
}
