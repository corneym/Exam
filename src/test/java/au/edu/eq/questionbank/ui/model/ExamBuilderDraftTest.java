package au.edu.eq.questionbank.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;

class ExamBuilderDraftTest {

	private final Subject chemistry = new Subject(1, "Chemistry");

	@Test
	void rejectsDuplicatesAcrossTheEntireExamination() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question original = createQuestion(1, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null, true);

		// Simulate a fresh retrieval of the same persistent
		// Question using a different Java object.
		Question retrievedAgain = createQuestion(1, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null,
				true);
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, original));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, retrievedAgain));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, retrievedAgain));
		assertEquals(1, draft.getTotalSelectedCount());
	}

	@Test
	void rejectsIncompleteAnswersAndIncorrectResponseTypes() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question mcq = createQuestion(1, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		Question unanswered = createQuestion(2, booklet, "8", 1, QuestionResponseType.MULTIPLE_CHOICE, null, false);
		Question written = createQuestion(3, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		assertTrue(draft.isEmpty());
		assertFalse(draft.canAdd(ExamBuilderDraft.Section.MULTIPLE_CHOICE, unanswered));
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, unanswered));
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, written));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, mcq));
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, mcq));
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, written));
		assertEquals(2, draft.getTotalSelectedCount());
	}

	@Test
	void rejectsQuestionsFromAnotherSubject() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		Subject physics = new Subject(2, "Physics");
		Question physicsQuestion = createQuestion(1, createBooklet(physics, 2), "7", 1,
				QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, physicsQuestion));
		assertTrue(draft.isEmpty());
	}

	@Test
	void reordersQuestionsAndRecalculatesGeneratedNumbering() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question mcq = createQuestion(1, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		Question partB = createQuestion(2, booklet, "21b", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partC = createQuestion(3, booklet, "21c", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question standalone = createQuestion(4, booklet, "25", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		var mcqSection = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		var writtenSection = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.add(mcqSection, mcq));
		assertTrue(draft.add(writtenSection, partB));
		assertTrue(draft.add(writtenSection, partC));
		assertTrue(draft.add(writtenSection, standalone));
		PrintableAssessmentPlan originalPlan = draft.buildPlan();
		assertEquals(List.of("2(a)", "2(b)", "3"),
				originalPlan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(9, draft.getTotalMarks(writtenSection));
		assertTrue(draft.move(writtenSection, standalone.getId(), 0));
		PrintableAssessmentPlan reordered = draft.buildPlan();
		assertEquals(List.of("2", "3(a)", "3(b)"),
				reordered.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertTrue(draft.remove(writtenSection, partB.getId()));
		PrintableAssessmentPlan reduced = draft.buildPlan();

		// Selecting just one part makes it a standalone
		// generated Question.
		assertEquals(List.of("2", "3"),
				reduced.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(7, draft.getTotalMarks(writtenSection));

		// A previously constructed plan must not change
		// when the editable draft changes.
		assertEquals(List.of("2(a)", "2(b)", "3"),
				originalPlan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals("21c", partC.getQuestionCode());
	}

	@Test
	void transfersWrittenQuestionsBetweenPapers() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question written = createQuestion(1, booklet, "21", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.add(paperOne, written));
		assertFalse(draft.transfer(paperOne, ExamBuilderDraft.Section.MULTIPLE_CHOICE, written.getId()));
		assertEquals(List.of(written), draft.getQuestions(paperOne));
		assertTrue(draft.transfer(paperOne, paperTwo, written.getId()));
		assertTrue(draft.getQuestions(paperOne).isEmpty());
		assertEquals(List.of(written), draft.getQuestions(paperTwo));
		assertEquals(0, draft.getTotalMarks(paperOne));
		assertEquals(4, draft.getTotalMarks(paperTwo));
		assertEquals("1", draft.buildPlan().getPaperTwo().getFirst().label());
		draft.clear();
		assertTrue(draft.isEmpty());
	}

	private ExamBooklet createBooklet(Subject subject, long id) {
		Exam exam = new Exam(id, subject, new ExamProvider(id, "QCAA"), 2025, "External");
		return new ExamBooklet(id, exam, "Paper 1", new SourceDocument(id, "questions.pdf"));
	}

	private Question createQuestion(long id, ExamBooklet booklet, String code, int marks,
			QuestionResponseType responseType, SourceQuestion source, boolean answered) {
		Subject subject = booklet.getExam().getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		Question question = new Question(id, booklet, code, "", marks,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, source, null,
				responseType);
		if (!answered) {
			return question;
		}
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			question.setAnswer(new Answer(id, "C", List.of()));
		} else {
			AnswerFile answerFile = new AnswerFile(1, booklet.getExam(), "Marking guide",
					new SourceDocument(10, "answers.pdf"));
			question.setAnswer(new Answer(id, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
		}
		return question;
	}
}
