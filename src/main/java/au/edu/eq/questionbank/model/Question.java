package au.edu.eq.questionbank.model;

import java.util.List;

public class Question {

	private final long id;
	private final Exam exam;
	private final String questionCode;
	private final String questionText;
	private final List<QuestionRegion> regions;

	public Question(long id, Exam exam, String questionCode, String questionText, List<QuestionRegion> regions) {

		this.id = id;
		this.exam = exam;
		this.questionCode = questionCode;
		this.questionText = questionText;
		this.regions = List.copyOf(regions);
	}

	public Exam getExam() {
		return exam;
	}

	public long getId() {
		return id;
	}

	public String getQuestionCode() {
		return questionCode;
	}

	public String getQuestionText() {
		return questionText;
	}

	public List<QuestionRegion> getRegions() {
		return regions;
	}
}