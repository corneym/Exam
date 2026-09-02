package au.edu.eq.questionbank.repository.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;

/**
 * Transient question repository that retains saved questions in insertion
 * order. Intended for proof-of-concept workflows and tests rather than durable
 * storage.
 */
public class InMemoryQuestionRepository implements QuestionRepository {

	private final List<Question> questions;
	private long nextId = 1;

	public InMemoryQuestionRepository() {
		questions = new ArrayList<Question>();
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
		Question question = new Question(nextId++, booklet, questionCode, questionText, marks, regions, classification,
				preambleCaptureRequired);
		questions.add(question);
		return question;
	}
}