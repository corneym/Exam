package au.edu.eq.questionbank.ui.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;

class ExamBuilderDraftTest {

	private final Subject chemistry = new Subject(1, "Chemistry");
	@TempDir
	Path tempDirectory;

	@Test
	void draftFileRejectsWrongSubjectAndMissingOrIncompleteQuestions() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("validation.exam.json");
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(
				id -> id == chemistry.getId() ? subjectUuid : UUID.randomUUID(), _ -> questionUuid,
				_ -> Optional.of(question));
		service.save(file, draft, settings);
		IOException wrongSubject = assertThrows(IOException.class, () -> service.load(file, new Subject(2, "Physics")));
		assertTrue(wrongSubject.getMessage().contains("another Subject"));
		ExamBuilderDraftFile missingQuestionService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.empty());
		IOException missingQuestion = assertThrows(IOException.class,
				() -> missingQuestionService.load(file, chemistry));
		assertTrue(missingQuestion.getMessage().contains("no longer exists"));
		Question incomplete = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, false);
		ExamBuilderDraftFile incompleteQuestionService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(incomplete));
		IOException incompleteAnswer = assertThrows(IOException.class,
				() -> incompleteQuestionService.load(file, chemistry));
		assertTrue(incompleteAnswer.getMessage().contains("no longer eligible"));

		// Rejected load operations must not alter the
		// examination already being edited.
		assertEquals(List.of(question), draft.getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
	}

	@Test
	void draftFilesPreserveOrderSettingsAndPortableIdentities() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question mcq = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partB = createQuestion(20, booklet, "21b", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partC = createQuestion(30, booklet, "21c", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, mcq));
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, partC));
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, partB));
		UUID subjectUuid = UUID.randomUUID();
		UUID mcqUuid = UUID.randomUUID();
		UUID partBUuid = UUID.randomUUID();
		UUID partCUuid = UUID.randomUUID();
		Map<Long, UUID> portableIds = Map.of(10L, mcqUuid, 20L, partBUuid, 30L, partCUuid);
		ExamBuilderDraftFile writer = new ExamBuilderDraftFile(_ -> subjectUuid, portableIds::get,
				_ -> Optional.empty());
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("chemistry.exam.json");
		writer.save(file, draft, settings);
		assertTrue(Files.isRegularFile(file));
		String json = Files.readString(file);
		assertTrue(json.contains(mcqUuid.toString()));
		assertTrue(json.contains(partBUuid.toString()));
		assertTrue(json.contains(partCUuid.toString()));

		// Simulate a restored corpus in which persistent
		// portable UUIDs are retained but local IDs have changed.
		Subject restoredSubject = new Subject(200, "Chemistry");
		ExamBooklet restoredBooklet = createBooklet(restoredSubject, 200);
		SourceQuestion restoredSource = new SourceQuestion(201, restoredBooklet, "21");
		Question restoredMcq = createQuestion(1010, restoredBooklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null,
				true);
		Question restoredPartB = createQuestion(1020, restoredBooklet, "21b", 2, QuestionResponseType.WRITTEN_RESPONSE,
				restoredSource, true);
		Question restoredPartC = createQuestion(1030, restoredBooklet, "21c", 3, QuestionResponseType.WRITTEN_RESPONSE,
				restoredSource, true);
		Map<UUID, Question> restoredQuestions = Map.of(mcqUuid, restoredMcq, partBUuid, restoredPartB, partCUuid,
				restoredPartC);
		ExamBuilderDraftFile reader = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> {
			throw new AssertionError("Not saving a draft.");
		}, id -> Optional.ofNullable(restoredQuestions.get(id)));
		ExamBuilderDraftFile.LoadedDraft loaded = reader.load(file, restoredSubject);
		assertEquals(settings, loaded.settings());
		assertEquals(restoredSubject, loaded.draft().getSubject());
		assertEquals(List.of(restoredMcq), loaded.draft().getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
		assertEquals(List.of(restoredPartC, restoredPartB),
				loaded.draft().getQuestions(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN));
		PrintableAssessmentPlan plan = loaded.draft().buildPlan();
		assertEquals(List.of("2(a)", "2(b)"),
				plan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(6, plan.getTotalMarks());
		assertEquals(10, mcq.getId());
		assertEquals(1010, restoredMcq.getId());
	}

	@Test
	void failedDraftSavePreservesExistingFile() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		Path file = tempDirectory.resolve("existing.exam.json");
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(question));
		service.save(file, draft, settings);
		byte[] original = Files.readAllBytes(file);
		ExamBuilderDraftFile failingService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> {
			throw new IllegalStateException("Missing Question identity");
		}, _ -> Optional.empty());
		assertThrows(IOException.class, () -> failingService.save(file, draft, settings));
		assertArrayEquals(original, Files.readAllBytes(file));
	}

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
