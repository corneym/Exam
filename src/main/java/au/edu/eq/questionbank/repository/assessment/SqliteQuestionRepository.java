package au.edu.eq.questionbank.repository.assessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.curriculum.CurriculumRepository;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * SQLite-backed question repository that reconstructs complete question,
 * region, exam, curriculum, and answer state from persistent records.
 */
public final class SqliteQuestionRepository implements QuestionRepository {

	private final SqliteDatabase database;
	private final SqliteQuestionWriter writer;
	private final CurriculumRepository curriculumRepository;

	/**
	 * Creates a repository for an initialised question-bank database.
	 *
	 * @param database the question-bank database
	 * @throws NullPointerException if {@code database} is {@code null}
	 */
	public SqliteQuestionRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}
		this.database = database;
		this.writer = new SqliteQuestionWriter(database);
		this.curriculumRepository = new SqliteCurriculumRepository(database);
	}

	@Override
	public List<Question> findAll() {

		List<Long> questionIds = new ArrayList<>();

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id
						FROM questions
						ORDER BY id
						""")) {

			while (result.next()) {
				questionIds.add(result.getLong("id"));
			}

		} catch (SQLException e) {
			throw new IllegalStateException("Could not read questions from database", e);
		}
		List<Question> questions = new ArrayList<>();
		for (Long questionId : questionIds) {
			Optional<Question> question = findById(questionId.longValue());
			if (question.isEmpty()) {
				throw new IllegalStateException("Question disappeared while reading database: " + questionId);
			}
			questions.add(question.get());
		}
		return questions;
	}

	@Override
	public Optional<Question> findById(long id) {

		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT
						    q.id,
						    q.question_code,
						    q.question_text,
						    q.marks,
						    q.preamble_capture_required,
						    q.classification_node_id,
						    eb.id AS booklet_id,
						    eb.booklet_name,
						    sd.id AS source_document_id,
						    sd.relative_path,
						    e.id AS exam_id,
						    e.exam_year,
						    e.exam_name,
						    s.id AS subject_id,
						    s.subject_name,
						    p.id AS provider_id,
						    p.provider_name,
						    cn.syllabus_version_id,
						    cn.curriculum_code
						FROM questions q
						JOIN exam_booklets eb
						    ON eb.id = q.booklet_id
						JOIN source_documents sd
						    ON sd.id = eb.source_document_id
						JOIN exams e
						    ON e.id = eb.exam_id
						JOIN subjects s
						    ON s.id = e.subject_id
						JOIN exam_providers p
						    ON p.id = e.provider_id
						JOIN curriculum_nodes cn
						    ON cn.id = q.classification_node_id
						WHERE q.id = ?
												""")) {

			statement.setLong(1, id);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}
				Subject subject = new Subject(result.getLong("subject_id"), result.getString("subject_name"));
				ExamProvider provider = new ExamProvider(result.getLong("provider_id"),
						result.getString("provider_name"));
				Exam exam = new Exam(result.getLong("exam_id"), subject, provider, result.getInt("exam_year"),
						result.getString("exam_name"));
				SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
						result.getString("relative_path"));
				ExamBooklet booklet = new ExamBooklet(result.getLong("booklet_id"), exam,
						result.getString("booklet_name"), sourceDocument);
				long syllabusVersionId = result.getLong("syllabus_version_id");
				SyllabusVersion syllabusVersion = curriculumRepository.findVersionById(syllabusVersionId)
						.orElseThrow(() -> new IllegalStateException("Missing syllabus version " + syllabusVersionId));
				String curriculumCode = result.getString("curriculum_code");
				CurriculumNode classification = curriculumRepository.findByCode(syllabusVersion, curriculumCode)
						.orElseThrow(() -> new IllegalStateException("Missing curriculum node " + curriculumCode));
				List<QuestionRegion> regions = findRegions(connection, id, booklet);
				Question question = new Question(result.getLong("id"), booklet, result.getString("question_code"),
						result.getString("question_text"), result.getInt("marks"), regions, classification,
						result.getInt("preamble_capture_required") != 0);
				Answer answer = findAnswer(connection, id, exam);
				if (answer != null) {
					question.setAnswer(answer);
				}
				return Optional.of(question);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read question " + id + " from database", e);
		}
	}

	@Override
	public Question save(ExamBooklet booklet, String questionCode, String questionText, int marks,
			List<QuestionRegion> regions, CurriculumNode classification, boolean preambleCaptureRequired) {
		try {
			return writer.insertQuestion(booklet, questionCode, questionText, marks, regions, classification,
					preambleCaptureRequired);

		} catch (SQLException e) {
			throw new IllegalStateException("Could not save question", e);
		}
	}

	private Answer findAnswer(Connection connection, long questionId, Exam exam) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT id, answer_text
				FROM answers
				WHERE question_id = ?
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return null;
				}
				long answerId = result.getLong("id");
				String answerText = result.getString("answer_text");
				List<AnswerRegion> regions = findAnswerRegions(connection, answerId, exam);
				return new Answer(answerId, answerText, regions);
			}
		}
	}

	private List<AnswerRegion> findAnswerRegions(Connection connection, long answerId, Exam exam) throws SQLException {
		List<AnswerRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    ar.page_number,
				    ar.x,
				    ar.y,
				    ar.width,
				    ar.height,
				    af.id AS answer_file_id,
				    af.exam_id AS answer_file_exam_id,
				    af.answer_file_name,
				    sd.id AS source_document_id,
				    sd.relative_path
				FROM answer_regions ar
				JOIN answer_files af
				    ON af.id = ar.answer_file_id
				JOIN source_documents sd
				    ON sd.id = af.source_document_id
				WHERE ar.answer_id = ?
				ORDER BY ar.region_order
				""")) {
			statement.setLong(1, answerId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					if (result.getLong("answer_file_exam_id") != exam.getId()) {
						throw new IllegalStateException("Answer region file belongs to a different exam");
					}
					SourceDocument sourceDocument = new SourceDocument(result.getLong("source_document_id"),
							result.getString("relative_path"));
					AnswerFile answerFile = new AnswerFile(result.getLong("answer_file_id"), exam,
							result.getString("answer_file_name"), sourceDocument);
					AnswerRegion region = new AnswerRegion(answerFile, result.getInt("page_number"),
							result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
							result.getDouble("height"));
					regions.add(region);
				}
			}
		}
		return regions;
	}

	private List<QuestionRegion> findRegions(Connection connection, long questionId, ExamBooklet questionBooklet)
			throws SQLException {
		List<QuestionRegion> regions = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT
				    qr.page_number,
				    qr.x,
				    qr.y,
				    qr.width,
				    qr.height,
				    eb.id AS booklet_id
				FROM question_regions qr
				JOIN exam_booklets eb
				    ON eb.id = qr.booklet_id
				WHERE qr.question_id = ?
				ORDER BY qr.region_order
				""")) {
			statement.setLong(1, questionId);
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					if (result.getLong("booklet_id") != questionBooklet.getId()) {
						throw new IllegalStateException("Question region belongs to a different booklet");
					}
					QuestionRegion region = new QuestionRegion(questionBooklet, result.getInt("page_number"),
							result.getDouble("x"), result.getDouble("y"), result.getDouble("width"),
							result.getDouble("height"));
					regions.add(region);
				}
			}
		}
		return regions;
	}
}
