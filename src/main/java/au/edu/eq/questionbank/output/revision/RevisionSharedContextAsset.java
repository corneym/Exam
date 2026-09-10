package au.edu.eq.questionbank.output.revision;

import java.nio.file.Path;

import au.edu.eq.questionbank.model.SharedQuestionContext;

/**
 * One rendered shared-context image in a static revision export.
 */
public final class RevisionSharedContextAsset {

	private final SharedQuestionContext sharedContext;
	private final Path relativePath;

	RevisionSharedContextAsset(SharedQuestionContext sharedContext, Path relativePath) {
		if (sharedContext == null) {
			throw new NullPointerException("sharedContext");
		}
		if (relativePath == null) {
			throw new NullPointerException("relativePath");
		}
		if (relativePath.isAbsolute() || relativePath.getRoot() != null) {
			throw new IllegalArgumentException("Shared-context asset path must be relative");
		}
		Path normalizedPath = relativePath.normalize();
		if (normalizedPath.startsWith("..")) {
			throw new IllegalArgumentException("Shared-context asset path must not escape the export root");
		}
		this.sharedContext = sharedContext;
		this.relativePath = normalizedPath;
	}

	public Path getRelativePath() {
		return relativePath;
	}

	public SharedQuestionContext getSharedContext() {
		return sharedContext;
	}
}
