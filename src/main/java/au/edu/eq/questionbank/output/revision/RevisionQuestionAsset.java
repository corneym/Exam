package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Question;

/**
 * One rendered question image in a static revision export.
 */
public final class RevisionQuestionAsset {

	private final Question question;
	private final Path relativePath;

	RevisionQuestionAsset(Question question, Path relativePath) {
		if (question == null) {
			throw new NullPointerException("question");
		}
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (question.getRegions().isEmpty()) {
			throw new IllegalArgumentException("Question asset requires at least one question region");
		}
		if (relativePath.isAbsolute() || relativePath.getRoot() != null) {
			throw new IllegalArgumentException("Question asset path must be relative");
		}
		Path normalizedPath = relativePath.normalize();
		if (normalizedPath.startsWith("..")) {
			throw new IllegalArgumentException("Question asset path must not escape the export root");
		}
		this.question = question;
		this.relativePath = normalizedPath;
	}

	/**
	 * Returns the stored question represented by this rendered image.
	 *
	 * @return the question represented by this image
	 */
	public Question getQuestion() {
		return question;
	}

	/**
	 * Returns the exported question image location within the generated site.
	 *
	 * @return the normalized image path relative to the export root
	 */
	public Path getRelativePath() {
		return relativePath;
	}
}
