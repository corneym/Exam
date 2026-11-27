package au.edu.eq.questionbank.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class PrintableQuestionNumberingTest {

	@Test
	void continuesPaperOneNumberingAfterMultipleChoice() {
		ExamBooklet booklet = createBooklet();
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		Question first = createQuestion(1, booklet, "12b", source);
		Question second = createQuestion(2, booklet, "12c", source);
		Question third = createQuestion(3, booklet, "19", null);
		var numbered = PrintableQuestionNumbering.number(List.of(first, second, third), 11);
		assertEquals(List.of("11(a)", "11(b)", "12"),
				numbered.stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
	}

	@Test
	void groupsAndRenumbersSelectedMultipartQuestions() {
		ExamBooklet booklet = createBooklet();
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		Question first = createQuestion(1, booklet, "12a", source);
		Question second = createQuestion(2, booklet, "12c", source);
		Question third = createQuestion(3, booklet, "19", null);
		var numbered = PrintableQuestionNumbering.number(List.of(first, second, third));
		assertEquals(List.of("1(a)", "1(b)", "2"),
				numbered.stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals("(a)", numbered.get(0).originalPartLabel());
		assertEquals("(c)", numbered.get(1).originalPartLabel());
		assertEquals("(b)", numbered.get(1).replacementPartLabel());

		// Original Question identities and codes remain unchanged.
		assertSame(second, numbered.get(1).question());
		assertEquals("12c", second.getQuestionCode());
	}

	@Test
	void plansBothPapersWithConsistentNumberingAndMarks() {
		ExamBooklet booklet = createBooklet();
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		SourceQuestion secondSource = new SourceQuestion(2, booklet, "18");
		Question mcqOne = createAnsweredQuestion(1, booklet, "7", null, QuestionResponseType.MULTIPLE_CHOICE);
		Question mcqTwo = createAnsweredQuestion(2, booklet, "9", null, QuestionResponseType.MULTIPLE_CHOICE);
		Question writtenOne = createAnsweredQuestion(3, booklet, "12b", source, QuestionResponseType.WRITTEN_RESPONSE);
		Question writtenTwo = createAnsweredQuestion(4, booklet, "12c", source, QuestionResponseType.WRITTEN_RESPONSE);
		Question writtenThree = createAnsweredQuestion(5, booklet, "19", null, QuestionResponseType.WRITTEN_RESPONSE);
		Question paperTwoQuestion = createAnsweredQuestion(6, booklet, "18c", secondSource,
				QuestionResponseType.WRITTEN_RESPONSE);
		PrintableAssessmentPlan plan = new PrintableAssessmentPlan(List.of(mcqOne, mcqTwo),
				List.of(writtenOne, writtenTwo, writtenThree), List.of(paperTwoQuestion));
		assertEquals(List.of("1", "2"),
				plan.getMultipleChoice().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(List.of("3(a)", "3(b)", "4"),
				plan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals("1", plan.getPaperTwo().getFirst().label());
		assertEquals("(c)", plan.getPaperTwo().getFirst().originalPartLabel());
		assertEquals("", plan.getPaperTwo().getFirst().replacementPartLabel());
		assertEquals(2, plan.getMultipleChoiceCount());
		assertEquals(2, plan.getMultipleChoiceMarks());

		// Three selected parts represent only two
		// top-level Paper 1 short-response Questions.
		assertEquals(2, plan.getPaperOneQuestionCount());
		assertEquals(6, plan.getPaperOneShortResponseMarks());
		assertEquals(8, plan.getPaperOneTotalMarks());
		assertEquals(1, plan.getPaperTwoQuestionCount());
		assertEquals(2, plan.getPaperTwoTotalMarks());
		assertEquals(10, plan.getTotalMarks());
	}

	@Test
	void rejectsInvalidAssessmentSelections() {
		ExamBooklet booklet = createBooklet();
		Question written = createAnsweredQuestion(1, booklet, "12", null, QuestionResponseType.WRITTEN_RESPONSE);
		Question unanswered = createQuestion(2, booklet, "13", null);

		// A Question cannot be selected in both papers.
		assertThrows(IllegalArgumentException.class,
				() -> new PrintableAssessmentPlan(List.of(), List.of(written), List.of(written)));

		// Written Questions cannot populate the MCQ grid.
		assertThrows(IllegalArgumentException.class,
				() -> new PrintableAssessmentPlan(List.of(written), List.of(), List.of()));

		// Selection requires a complete Answer.
		assertThrows(IllegalArgumentException.class,
				() -> new PrintableAssessmentPlan(List.of(), List.of(unanswered), List.of()));
	}

	@Test
	void removesSinglePartLabelAndRestartsNumbering() {
		ExamBooklet booklet = createBooklet();
		SourceQuestion source = new SourceQuestion(1, booklet, "12");
		Question first = createQuestion(1, booklet, "12b", source);
		Question second = createQuestion(2, booklet, "19", null);
		var numbered = PrintableQuestionNumbering.number(List.of(first, second));
		assertEquals("1", numbered.get(0).label());
		assertEquals("(b)", numbered.get(0).originalPartLabel());
		assertEquals("", numbered.get(0).replacementPartLabel());
		assertEquals("2", numbered.get(1).label());

		// A separate booklet starts numbering again at one.
		var nextBooklet = PrintableQuestionNumbering.number(List.of(second));
		assertEquals("1", nextBooklet.getFirst().label());
	}

	private Question createAnsweredQuestion(long id, ExamBooklet booklet, String code, SourceQuestion source,
			QuestionResponseType responseType) {
		Question question = createQuestion(id, booklet, code, source);
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			Question mcq = new Question(id, booklet, code, "", 1, question.getRegions(), question.getClassification(),
					false, source, null, QuestionResponseType.MULTIPLE_CHOICE);
			mcq.setAnswer(new Answer(id, "C", List.of()));
			return mcq;
		}
		AnswerFile answerFile = new AnswerFile(1, booklet.getExam(), "Marking guide",
				new SourceDocument(2, "answers.pdf"));
		question.setAnswer(new Answer(id, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
		return question;
	}

	private ExamBooklet createBooklet() {
		Subject subject = new Subject(1, "Chemistry");
		ExamProvider provider = new ExamProvider(1, "QCAA");
		Exam exam = new Exam(1, subject, provider, 2026, "External");
		return new ExamBooklet(1, exam, "Booklet 2", new SourceDocument(1, "source.pdf"));
	}

	private Question createQuestion(long id, ExamBooklet booklet, String code, SourceQuestion source) {
		Subject subject = booklet.getExam().getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		return new Question(id, booklet, code, "", 2, List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)),
				subtopic, false, source, null, QuestionResponseType.WRITTEN_RESPONSE);
	}
}
