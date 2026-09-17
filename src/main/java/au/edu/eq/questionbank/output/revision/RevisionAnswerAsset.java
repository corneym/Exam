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

	/**
	 * Returns the source region represented by this answer image.
	 *
	 * @return the authoritative answer source region
	 */
	public AnswerRegion getAnswerRegion() {
		return answerRegion;
	}

	/**
	 * Returns the question answered by this image.
	 *
	 * @return the question whose answer region was rendered
	 */
	public Question getQuestion() {
		return question;
	}

	/**
	 * Returns the image's position in the answer region sequence.
	 *
	 * @return the one-based position within the ordered answer regions
	 */
	public int getRegionNumber() {
		return regionNumber;
	}

	/**
	 * Returns the exported answer image location within the generated site.
	 *
	 * @return the normalized image path relative to the export root
	 */
	public Path getRelativePath() {
		return relativePath;
	}
}
