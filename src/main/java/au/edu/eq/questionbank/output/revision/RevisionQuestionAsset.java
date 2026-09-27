package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.Question;

/**
 * One rendered Question-body image in a static revision export.
 * <p>
 * The rendered body may be assembled from PDF regions, stored images, or a
 * mixture of both in the Question's authoritative content order.
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
		if (question.getContentParts().isEmpty()) {

			// A revision asset requires student-facing Question body content. That content
			// may be PDF-backed, image-backed, or a mixture of both.
			throw new IllegalArgumentException("Question asset requires at least one content part");
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
