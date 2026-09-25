package au.edu.eq.questionbank.service.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContextRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.NewSharedContext;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitPart;
import au.edu.eq.questionbank.repository.assessment.LegacyQuestionSplitService.SplitRequest;
import au.edu.eq.questionbank.repository.assessment.SqliteExamImporter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class QuestionCorpusAuditTest {

	private final Fixture fixture = new Fixture();
	@TempDir
	Path tempDirectory;

	@Test
	void completeMultipleChoiceRequiresOnlyValidLetter() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, false);
		question.setAnswer(new Answer(101, "B", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertTrue(status.answerComplete());
		assertTrue(status.isComplete());
	}

	@Test
	void completeWrittenResponseHasNoProblems() {
		Question question = fixture.question(QuestionResponseType.WRITTEN_RESPONSE, true, false);
		question.setAnswer(new Answer(100, null, List.of(fixture.answerRegion())));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertTrue(status.questionSourceCaptured());
		assertTrue(status.responseTypeResolved());
		assertTrue(status.answerComplete());
		assertTrue(status.sharedContextResolved());
		assertTrue(status.isComplete());
		assertTrue(status.problems().isEmpty());
	}

	@Test
	void missingQuestionRegionIsReportedIndependently() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, false, false);
		question.setAnswer(new Answer(102, "A", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.questionSourceCaptured());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_QUESTION_SOURCE));
		assertFalse(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void multipleChoiceRegionWithoutValidLetterIsIncomplete() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, false);
		question.setAnswer(new Answer(105, null, List.of(fixture.answerRegion())));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void reloadedSplitQuestionsHaveResolvedSharedContextForCorpusAudit() throws Exception {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve("split-corpus-audit.db"));
		database.initialiseSchema();
		SqliteCurriculumWriter curriculumWriter = new SqliteCurriculumWriter(database);
		Subject chemistry = curriculumWriter.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculumWriter.insertSyllabusVersion(chemistry, "2019", false);
		Unit unit = curriculumWriter.insertUnit(syllabus, "1", "Unit 1", 1);
		Topic topic = curriculumWriter.insertTopic(unit, "1.1", "Topic 1", 1);
		Descriptor classification = curriculumWriter.insertDescriptor(topic, "1.1.1", "Descriptor 1", 1);
		SqliteExamWriter examWriter = new SqliteExamWriter(database);
		ExamBooklet booklet = new SqliteExamImporter(database, examWriter).importExam(chemistry, "QCAA", 2019,
				"External Assessment", "Paper 1", "Chemistry/2019/paper1.pdf");
		SqliteQuestionRepository questionRepository = new SqliteQuestionRepository(database);
		Question original = questionRepository.save(booklet, "3", "", 5,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.80, 0.60)), classification, true, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
		SplitPart partA = new SplitPart("3a", 2, classification, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 1, 0.10, 0.30, 0.80, 0.20)));
		SplitPart partB = new SplitPart("3b", 3, classification, QuestionResponseType.WRITTEN_RESPONSE,
				List.of(new QuestionRegion(booklet, 2, 0.10, 0.20, 0.80, 0.25)));
		LegacyQuestionSplitService.SplitResult split = new LegacyQuestionSplitService(database)
				.split(new SplitRequest(original, "3", List.of(partA, partB), 0,
						new NewSharedContext(List.of(new SharedQuestionContextRegion(1, 0.10, 0.10, 0.80, 0.15)))));

		// Use a fresh repository so Corpus Audit sees reconstructed persistence state,
		// not the objects returned directly by the split transaction.
		SqliteQuestionRepository reloadedRepository = new SqliteQuestionRepository(database);
		Question reloadedA = reloadedRepository.findById(split.questions().get(0).getId()).orElseThrow();
		Question reloadedB = reloadedRepository.findById(split.questions().get(1).getId()).orElseThrow();
		assertTrue(reloadedA.hasSourceQuestion());
		assertTrue(reloadedB.hasSourceQuestion());
		assertTrue(reloadedA.hasSharedContext());
		assertTrue(reloadedB.hasSharedContext());
		assertTrue(reloadedA.getSourceQuestion().getId() == reloadedB.getSourceQuestion().getId());
		assertTrue(reloadedA.getSharedContext().getId() == reloadedB.getSharedContext().getId());
		QuestionCorpusStatus statusA = QuestionCorpusAudit.assess(reloadedA);
		QuestionCorpusStatus statusB = QuestionCorpusAudit.assess(reloadedB);

		// The split parts have captured Question source regions and their persisted
		// shared context is fully resolved after reconstruction.
		assertTrue(statusA.questionSourceCaptured());
		assertTrue(statusB.questionSourceCaptured());
		assertTrue(statusA.sharedContextResolved());
		assertTrue(statusB.sharedContextResolved());
		assertFalse(statusA.hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT));
		assertFalse(statusB.hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT));
	}

	@Test
	void unknownResponseTypeDoesNotAlsoReportMissingAnswer() {
		Question question = fixture.question(QuestionResponseType.UNKNOWN, true, false);
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.responseTypeResolved());
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE));
		assertFalse(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	@Test
	void unresolvedSharedContextIsReportedIndependently() {
		Question question = fixture.question(QuestionResponseType.MULTIPLE_CHOICE, true, true);
		question.setAnswer(new Answer(103, "C", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.sharedContextResolved());
		assertTrue(status.hasProblem(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT));
		assertFalse(status.isComplete());
	}

	@Test
	void writtenResponseTextWithoutRegionIsIncomplete() {
		Question question = fixture.question(QuestionResponseType.WRITTEN_RESPONSE, true, false);
		question.setAnswer(new Answer(104, "legacy text", List.of()));
		QuestionCorpusStatus status = QuestionCorpusAudit.assess(question);
		assertFalse(status.answerComplete());
		assertTrue(status.hasProblem(QuestionCorpusProblem.MISSING_ANSWER));
	}

	private static final class Fixture {

		private final Subject subject = new Subject(1, "Chemistry");
		private final SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
		private final Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
		private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
		private final Descriptor classification = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor 1", 1);
		private final ExamProvider provider = new ExamProvider(6, "QCAA");
		private final Exam exam = new Exam(7, subject, provider, 2025, "External Assessment");
		private final ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1",
				new SourceDocument(9, "Chemistry/2025/paper1.pdf"));
		private final AnswerFile answerFile = new AnswerFile(10, exam, "Marking guide",
				new SourceDocument(11, "Chemistry/2025/answers.pdf"));

		private AnswerRegion answerRegion() {
			return new AnswerRegion(answerFile, 1, 0.10, 0.20, 0.70, 0.20);
		}

		private Question question(QuestionResponseType responseType, boolean withQuestionRegion,
				boolean sharedContextCaptureRequired) {
			List<QuestionRegion> regions = withQuestionRegion
					? List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20))
					: List.of();

			// Multiple-choice audit fixtures must remain valid domain Questions so each
			// test exercises the audit condition it was actually written to inspect.
			int marks = responseType == QuestionResponseType.MULTIPLE_CHOICE ? 1 : 2;
			return new Question(20, booklet, "Q1", "", marks, regions, classification, sharedContextCaptureRequired,
					null, null, responseType);
		}
	}
}
