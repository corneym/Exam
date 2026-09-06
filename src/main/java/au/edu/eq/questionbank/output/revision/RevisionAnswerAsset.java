package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Question;

/**
 * One rendered answer-region image in a static revision export.
 */
public final class RevisionAnswerAsset {

	private final Question question;
	private final AnswerRegion answerRegion;
	private final int regionNumber;
	private final Path relativePath;

	RevisionAnswerAsset(Question question, AnswerRegion answerRegion, int regionNumber, Path relativePath) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (answerRegion == null) {
			throw new NullPointerException("answerRegion");
		}
		if (regionNumber < 1) {
			throw new IllegalArgumentException("regionNumber must be positive");
		}
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (relativePath.isAbsolute() || relativePath.getRoot() != null) {
			throw new IllegalArgumentException("Answer asset path must be relative");
		}
		Path normalizedPath = relativePath.normalize();
		if (normalizedPath.startsWith("..")) {
			throw new IllegalArgumentException("Answer asset path must not escape the export root");
		}
		this.question = question;
		this.answerRegion = answerRegion;
		this.regionNumber = regionNumber;
		this.relativePath = normalizedPath;
	}

	public AnswerRegion getAnswerRegion() {
		return answerRegion;
	}

	public Question getQuestion() {
		return question;
	}

	public int getRegionNumber() {
		return regionNumber;
	}

	public Path getRelativePath() {
		return relativePath;
	}
}
