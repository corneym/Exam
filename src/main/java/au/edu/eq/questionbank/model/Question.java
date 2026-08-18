package au.edu.eq.questionbank.model;

public class Question {

	private final long id;
	private final Exam exam;
	private final String questionCode;
	private final String questionText;
	private final int pageNumber;

	public Question(long id, Exam exam, String questionCode, String questionText, int pageNumber) {

		this.id = id;
		this.exam = exam;
		this.questionCode = questionCode;
		this.questionText = questionText;
		this.pageNumber = pageNumber;
	}

	public Exam getExam() {
		return exam;
	}

	public long getId() {
		return id;
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public String getQuestionCode() {
		return questionCode;
	}

	public String getQuestionText() {
		return questionText;
	}
}