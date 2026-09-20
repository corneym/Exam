package au.edu.eq.questionbank.ui.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.service.audit.QuestionCorpusProblem;
import au.edu.eq.questionbank.service.audit.QuestionCorpusStatus;
import au.edu.eq.questionbank.service.audit.QuestionCorpusWorkItem;

class QuestionCorpusAuditDialogTest {

	private final Question question = createQuestion();

	private static Question createQuestion() {
		Subject subject = new Subject(1, "Chemistry");
		SyllabusVersion syllabus = new SyllabusVersion(2, subject, "2025", true);
		Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
		Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
		Descriptor descriptor = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor", 1);
		Exam exam = new Exam(6, subject, new ExamProvider(7, "QCAA"), 2025, "External Assessment");
		ExamBooklet booklet = new ExamBooklet(8, exam, "Paper 1", new SourceDocument(9, "Chemistry/2025/paper1.pdf"));
		return new Question(10, booklet, "Q1", "", 1, List.of(new QuestionRegion(booklet, 1, 0.10, 0.10, 0.70, 0.20)),
				descriptor, false, null, null, QuestionResponseType.WRITTEN_RESPONSE);
	}

	@Test
	void completeItemHasNoResolutionTarget() {
		QuestionCorpusStatus status = new QuestionCorpusStatus(true, true, true, true,
				EnumSet.noneOf(QuestionCorpusProblem.class));
		QuestionCorpusWorkItem item = new QuestionCorpusWorkItem(question, status);
		assertNull(QuestionCorpusAuditDialog.resolutionTarget(item));
	}

	@Test
	void missingAnswerRoutesToAnswerCapture() {
		QuestionCorpusWorkItem item = item(QuestionCorpusProblem.MISSING_ANSWER);
		assertEquals(QuestionCorpusAuditDialog.ResolutionTarget.ANSWER,
				QuestionCorpusAuditDialog.resolutionTarget(item));
	}

	@Test
	void missingQuestionSourceRoutesToQuestionCaptureBeforeAnswer() {
		QuestionCorpusWorkItem item = item(QuestionCorpusProblem.MISSING_QUESTION_SOURCE,
				QuestionCorpusProblem.MISSING_ANSWER);
		assertEquals(QuestionCorpusAuditDialog.ResolutionTarget.QUESTION,
				QuestionCorpusAuditDialog.resolutionTarget(item));
	}

	@Test
	void unknownResponseTypeRoutesToMetadataFirst() {
		QuestionCorpusWorkItem item = item(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE,
				QuestionCorpusProblem.MISSING_QUESTION_SOURCE, QuestionCorpusProblem.MISSING_ANSWER);
		assertEquals(QuestionCorpusAuditDialog.ResolutionTarget.METADATA,
				QuestionCorpusAuditDialog.resolutionTarget(item));
	}

	@Test
	void unresolvedSharedContextRoutesToQuestionCapture() {
		QuestionCorpusWorkItem item = item(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT);
		assertEquals(QuestionCorpusAuditDialog.ResolutionTarget.QUESTION,
				QuestionCorpusAuditDialog.resolutionTarget(item));
	}

	private QuestionCorpusWorkItem item(QuestionCorpusProblem... problems) {
		EnumSet<QuestionCorpusProblem> set = EnumSet.noneOf(QuestionCorpusProblem.class);
		set.addAll(List.of(problems));
		QuestionCorpusStatus status = new QuestionCorpusStatus(
				!set.contains(QuestionCorpusProblem.MISSING_QUESTION_SOURCE),
				!set.contains(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE),
				!set.contains(QuestionCorpusProblem.MISSING_ANSWER)
						&& !set.contains(QuestionCorpusProblem.UNKNOWN_RESPONSE_TYPE),
				!set.contains(QuestionCorpusProblem.UNRESOLVED_SHARED_CONTEXT), set);
		return new QuestionCorpusWorkItem(question, status);
	}
}
