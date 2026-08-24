package au.edu.eq.questionbank.model;

import java.util.List;

public class Question {

	private final long id;
	private final Exam exam;
	private final String questionCode;
	private final String questionText;
	private final List<QuestionRegion> regions;
	private final CurriculumNode classification;

	public Question(long id, Exam exam, String questionCode, String questionText, List<QuestionRegion> regions,
			CurriculumNode classification) {

		if (regions == null) {
			throw new NullPointerException("regions");
		}
		if (classification == null) {
			throw new NullPointerException("classification");
		}
		if (classification.getLevel() != CurriculumLevel.SUBTOPIC) {
			throw new IllegalArgumentException("Question classification must be a SUBTOPIC");
		}
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("Question must contain at least one region");
		}
		this.id = id;
		this.exam = exam;
		this.questionCode = questionCode;
		this.questionText = questionText;
		this.regions = List.copyOf(regions);
		this.classification = classification;
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

	public CurriculumNode getClassification() {
		return classification;
	}
}