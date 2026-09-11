package au.edu.eq.questionbank.repository.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.model.SourceQuestion;

/**
 * Transient question repository that retains saved questions in insertion
 * order. Intended for proof-of-concept workflows and tests rather than durable
 * storage.
 */
public class InMemoryQuestionRepository implements QuestionRepository {

	private final List<Question> questions;
	private long nextId = 1;

	/**
	 * Creates an empty repository.
	 */
	public InMemoryQuestionRepository() {
		questions = new ArrayList<Question>();
	}

	@Override
	public int applySharedContextToSourceQuestion(SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		if (sourceQuestion == null) {
			throw new NullPointerException("sourceQuestion");
		}
		if (sharedContext == null) {
			throw new NullPointerException("sharedContext");
		}
		if (sharedContext.getBooklet().getId() != sourceQuestion.getBooklet().getId()) {
			throw new IllegalArgumentException("Shared question context must belong to the source question's booklet");
		}
		for (Question question : questions) {
			if (!question.hasSourceQuestion() || question.getSourceQuestion().getId() != sourceQuestion.getId()) {
				continue;
			}
			if (question.getBooklet().getId() != sourceQuestion.getBooklet().getId()) {
				throw new IllegalStateException("Question belongs to a different booklet from its source question");
			}
			if (question.hasSharedContext() && question.getSharedContext().getId() != sharedContext.getId()) {
				throw new IllegalStateException("Source question " + sourceQuestion.getSourceQuestionCode()
						+ " has inconsistent shared preamble links");
			}
		}
		int updatedCount = 0;
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (!existing.hasSourceQuestion() || existing.getSourceQuestion().getId() != sourceQuestion.getId()
					|| existing.hasSharedContext()) {
				continue;
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), existing.getQuestionCode(),
					existing.getQuestionText(), existing.getMarks(), existing.getRegions(),
					existing.getClassification(), existing.isPreambleCaptureRequired(), existing.getSourceQuestion(),
					sharedContext);
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			updatedCount++;
		}
		return updatedCount;
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions) {
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (existing.getId() != questionId) {
				continue;
			}
			if (!existing.getRegions().isEmpty()) {
				throw new IllegalArgumentException("Question already has captured regions");
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), existing.getQuestionCode(),
					existing.getQuestionText(), existing.getMarks(), regions, existing.getClassification(),
					existing.isPreambleCaptureRequired(), existing.getSourceQuestion(), existing.getSharedContext());
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			return updated;
		}
		throw new IllegalArgumentException("Question does not exist: " + questionId);
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (existing.getId() != questionId) {
				continue;
			}
			if (!existing.getRegions().isEmpty()) {
				throw new IllegalArgumentException("Question already has captured regions");
			}
			if (classification.getSyllabusVersion().getId() != existing.getClassification().getSyllabusVersion()
					.getId()) {
				throw new IllegalArgumentException(
						"Imported question classification must remain in its existing syllabus");
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), existing.getQuestionCode(),
					existing.getQuestionText(), existing.getMarks(), regions, classification,
					existing.isPreambleCaptureRequired(), sourceQuestion, sharedContext);
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			return updated;
		}
		throw new IllegalArgumentException("Question does not exist: " + questionId);
	}

	@Override
	public Question attachRegions(long questionId, List<QuestionRegion> regions, SourceQuestion sourceQuestion,
			SharedQuestionContext sharedContext) {
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (existing.getId() != questionId) {
				continue;
			}
			if (!existing.getRegions().isEmpty()) {
				throw new IllegalArgumentException("Question already has captured regions");
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), existing.getQuestionCode(),
					existing.getQuestionText(), existing.getMarks(), regions, existing.getClassification(),
					existing.isPreambleCaptureRequired(), sourceQuestion, sharedContext);
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			return updated;
		}
		throw new IllegalArgumentException("Question does not exist: " + questionId);
	}

	@Override
	public List<Question> findAll() {
		return List.copyOf(questions);
	}

	@Override
	public Optional<Question> findById(long id) {
		for (Question question : questions) {
			if (question.getId() == id) {
				return Optional.of(question);
			}
		}
		return Optional.empty();
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired) {
		return save(booklet, questionCode, questionText, marks, regions, classification, preambleCaptureRequired, null,
				null);
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		Question question = new Question(nextId++, booklet, questionCode, questionText, marks, regions, classification,
				preambleCaptureRequired, sourceQuestion, sharedContext);
		questions.add(question);
		return question;
	}

	@Override
	public Question updateCaptureRelationships(long questionId, CurriculumNode classification,
			SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (existing.getId() != questionId) {
				continue;
			}
			if (classification.getSyllabusVersion().getId() != existing.getClassification().getSyllabusVersion()
					.getId()) {
				throw new IllegalArgumentException(
						"Imported question classification must remain in its existing syllabus");
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), existing.getQuestionCode(),
					existing.getQuestionText(), existing.getMarks(), existing.getRegions(), classification,
					existing.isPreambleCaptureRequired(), sourceQuestion, sharedContext);
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			return updated;
		}
		throw new IllegalArgumentException("Question does not exist: " + questionId);
	}

	@Override
	public Question updateQuestion(long questionId, String questionCode, int marks, List<QuestionRegion> regions,
			CurriculumNode classification, SourceQuestion sourceQuestion, SharedQuestionContext sharedContext) {
		if (questionCode == null || questionCode.isBlank()) {
			throw new IllegalArgumentException("questionCode must not be blank");
		}
		if (marks < 1) {
			throw new IllegalArgumentException("marks must be positive");
		}
		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("regions must not be empty");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		for (int i = 0; i < questions.size(); i++) {
			Question existing = questions.get(i);
			if (existing.getId() != questionId) {
				continue;
			}
			if (classification.getSyllabusVersion().getId() != existing.getClassification().getSyllabusVersion()
					.getId()) {
				throw new IllegalArgumentException("Question classification must remain in its existing syllabus");
			}
			for (QuestionRegion region : regions) {
				if (region == null) {
					throw new NullPointerException("regions contains null");
				}
				if (region.booklet().getId() != existing.getBooklet().getId()) {
					throw new IllegalArgumentException("All question regions must belong to the question's booklet");
				}
			}
			if (sourceQuestion != null && sourceQuestion.getBooklet().getId() != existing.getBooklet().getId()) {
				throw new IllegalArgumentException("Source question must belong to the question's booklet");
			}
			if (sharedContext != null && sharedContext.getBooklet().getId() != existing.getBooklet().getId()) {
				throw new IllegalArgumentException("Shared question context must belong to the question's booklet");
			}
			Question updated = new Question(existing.getId(), existing.getBooklet(), questionCode,
					existing.getQuestionText(), marks, regions, classification, existing.isPreambleCaptureRequired(),
					sourceQuestion, sharedContext);
			if (existing.hasAnswer()) {
				updated.setAnswer(existing.getAnswer());
			}
			questions.set(i, updated);
			return updated;
		}
		throw new IllegalArgumentException("Question does not exist: " + questionId);
	}
}