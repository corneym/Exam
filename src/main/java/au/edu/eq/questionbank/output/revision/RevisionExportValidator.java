package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Validates a generated static revision export before it is promoted from
 * staging to its completed destination.
 */
public final class RevisionExportValidator {

	/**
	 * Creates a validator for generated revision pages and their local assets.
	 */
	public RevisionExportValidator() {
	}

	private static final Pattern REFERENCE_PATTERN = Pattern.compile("(?:href|src)\\s*=\\s*\"([^\"]+)\"",
			Pattern.CASE_INSENSITIVE);

	/**
	 * Checks generated pages, local references and required source-derived assets before publication.
	 *
	 * @param exportRoot staged revision-site directory
	 * @param corpus source corpus defining required question and answer content
	 * @param htmlFiles generated HTML file paths beneath the export root
	 * @param questionAssets rendered question images
	 * @param answerAssets rendered answer-region images
	 * @param sharedContextAssets rendered reusable preamble images
	 * @throws IOException if generated files or references fail validation
	 */
	public void validate(Path exportRoot, RevisionCorpus corpus, List<Path> htmlFiles,
			List<RevisionQuestionAsset> questionAssets, List<RevisionAnswerAsset> answerAssets,
			List<RevisionSharedContextAsset> sharedContextAssets) throws IOException {
		if (exportRoot == null) {
			throw new NullPointerException("exportRoot");
		}
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (htmlFiles == null) {
			throw new NullPointerException("htmlFiles");
		}
		if (questionAssets == null) {
			throw new NullPointerException("questionAssets");
		}
		if (answerAssets == null) {
			throw new NullPointerException("answerAssets");
		}
		Path root = exportRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("Revision export root does not exist: " + root);
		}
		if (sharedContextAssets == null) {
			throw new NullPointerException("sharedContextAssets");
		}
		validateSubjectIndex(root);
		validateHtmlFiles(root, htmlFiles);
		validateQuestionAssets(root, corpus, questionAssets);
		validateSharedContextAssets(root, corpus, sharedContextAssets);
		validateAnswerAssets(root, corpus, answerAssets);
		validateHtmlReferences(root, htmlFiles);
	}

	private void collectExpectedAnswerRegions(List<RevisionCorpusNode> nodes, Set<String> expectedRegions) {
		for (RevisionCorpusNode node : nodes) {
			for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
				Question question = placement.getQuestion();
				if (!question.hasAnswer()) {
					continue;
				}
				Answer answer = question.getAnswer();
				for (int index = 0; index < answer.getRegions().size(); index++) {
					expectedRegions.add(question.getId() + ":" + (index + 1));
				}
			}
			collectExpectedAnswerRegions(node.getChildren(), expectedRegions);
		}
	}

	private void collectExpectedQuestionIds(List<RevisionCorpusNode> nodes, Set<Long> expectedQuestionIds) {
		for (RevisionCorpusNode node : nodes) {
			for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
				if (placement.isRenderable()) {
					expectedQuestionIds.add(placement.getQuestion().getId());
				}
			}
			collectExpectedQuestionIds(node.getChildren(), expectedQuestionIds);
		}
	}

	private void collectExpectedSharedContextIds(List<RevisionCorpusNode> nodes, Set<Long> expectedContextIds) {
		for (RevisionCorpusNode node : nodes) {
			for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
				if (!placement.isRenderable()) {
					continue;
				}
				Question question = placement.getQuestion();
				if (question.hasSharedContext()) {
					expectedContextIds.add(question.getSharedContext().getId());
				}
			}
			collectExpectedSharedContextIds(node.getChildren(), expectedContextIds);
		}
	}

	private void validateAnswerAssets(Path root, RevisionCorpus corpus, List<RevisionAnswerAsset> answerAssets)
			throws IOException {
		Set<String> claimedPaths = new HashSet<String>();
		Set<String> suppliedRegions = new HashSet<String>();
		for (RevisionAnswerAsset asset : answerAssets) {
			if (asset == null) {
				throw new IOException("Answer asset list contains null");
			}
			Path relativePath = asset.getRelativePath();
			validateClaimedRelativePath(relativePath, "answer asset", claimedPaths);
			Path file = root.resolve(relativePath).normalize();
			validateInsideRoot(root, file, "Answer asset");
			validateNonEmptyFile(file, "Answer asset");
			String regionKey = asset.getQuestion().getId() + ":" + asset.getRegionNumber();
			if (!suppliedRegions.add(regionKey)) {
				throw new IOException("Duplicate answer asset for question " + asset.getQuestion().getId() + ", region "
						+ asset.getRegionNumber());
			}
		}
		Set<String> expectedRegions = new HashSet<String>();
		collectExpectedAnswerRegions(corpus.getRootNodes(), expectedRegions);
		if (!suppliedRegions.equals(expectedRegions)) {
			throw new IOException("Answer assets do not match persisted answer regions. Expected " + expectedRegions
					+ " but found " + suppliedRegions);
		}
	}

	private void validateClaimedRelativePath(Path relativePath, String description, Set<String> claimedPaths)
			throws IOException {
		if (relativePath == null) {
			throw new IOException(description + " path is null");
		}
		if (relativePath.isAbsolute() || relativePath.getRoot() != null) {
			throw new IOException(description + " path must be relative: " + relativePath);
		}
		Path normalized = relativePath.normalize();
		if (normalized.startsWith("..")) {
			throw new IOException(description + " path escapes the export root: " + relativePath);
		}
		String key = normalized.toString().replace('\\', '/');
		if (!claimedPaths.add(key)) {
			throw new IOException("Duplicate " + description + " output path: " + key);
		}
	}

	private void validateHtmlFiles(Path root, List<Path> htmlFiles) throws IOException {
		Set<Path> uniqueFiles = new HashSet<Path>();
		for (Path htmlFile : htmlFiles) {
			if (htmlFile == null) {
				throw new IOException("HTML file list contains null");
			}
			Path normalized = htmlFile.toAbsolutePath().normalize();
			validateInsideRoot(root, normalized, "HTML file");
			if (!uniqueFiles.add(normalized)) {
				throw new IOException("Duplicate HTML output path: " + normalized);
			}
			validateNonEmptyFile(normalized, "HTML file");
		}
	}

	private void validateHtmlReferences(Path root, List<Path> htmlFiles) throws IOException {
		for (Path htmlFile : htmlFiles) {
			Path normalizedHtml = htmlFile.toAbsolutePath().normalize();
			String html = Files.readString(normalizedHtml);
			Matcher matcher = REFERENCE_PATTERN.matcher(html);
			while (matcher.find()) {
				String reference = matcher.group(1);
				validateReference(root, normalizedHtml, reference);
			}
		}
	}

	private void validateInsideRoot(Path root, Path path, String description) throws IOException {
		if (!path.startsWith(root)) {
			throw new IOException(description + " escapes the export root: " + path);
		}
	}

	private void validateNonEmptyFile(Path file, String description) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException(description + " does not exist: " + file);
		}
		if (Files.size(file) == 0) {
			throw new IOException(description + " is empty: " + file);
		}
	}

	private void validateQuestionAssets(Path root, RevisionCorpus corpus, List<RevisionQuestionAsset> questionAssets)
			throws IOException {
		Set<String> claimedPaths = new HashSet<String>();
		Set<Long> suppliedQuestionIds = new HashSet<Long>();
		for (RevisionQuestionAsset asset : questionAssets) {
			if (asset == null) {
				throw new IOException("Question asset list contains null");
			}
			Path relativePath = asset.getRelativePath();
			validateClaimedRelativePath(relativePath, "question asset", claimedPaths);
			Path file = root.resolve(relativePath).normalize();
			validateInsideRoot(root, file, "Question asset");
			validateNonEmptyFile(file, "Question asset");
			long questionId = asset.getQuestion().getId();
			if (!suppliedQuestionIds.add(questionId)) {
				throw new IOException("Duplicate question asset for question " + questionId);
			}
		}
		Set<Long> expectedQuestionIds = new HashSet<Long>();
		collectExpectedQuestionIds(corpus.getRootNodes(), expectedQuestionIds);
		if (!suppliedQuestionIds.equals(expectedQuestionIds)) {
			throw new IOException("Question assets do not match renderable questions. Expected " + expectedQuestionIds
					+ " but found " + suppliedQuestionIds);
		}
	}

	private void validateReference(Path root, Path htmlFile, String reference) throws IOException {
		if (reference == null || reference.isBlank()) {
			throw new IOException("Generated HTML contains an empty reference: " + htmlFile);
		}
		String lowerReference = reference.toLowerCase();
		if (lowerReference.startsWith("http:") || lowerReference.startsWith("https:")
				|| lowerReference.startsWith("file:") || lowerReference.startsWith("data:")
				|| lowerReference.startsWith("javascript:") || reference.startsWith("/") || reference.startsWith("\\")
				|| reference.matches("^[A-Za-z]:.*")) {
			throw new IOException("Generated HTML contains an absolute or external reference: " + reference);
		}
		if (reference.contains("#") || reference.contains("?")) {
			throw new IOException("Generated HTML reference must be a plain relative file path: " + reference);
		}
		Path target = htmlFile.getParent().resolve(reference.replace('/', java.io.File.separatorChar)).normalize();
		validateInsideRoot(root, target, "HTML reference");
		validateNonEmptyFile(target, "HTML reference");
	}

	private void validateSharedContextAssets(Path root, RevisionCorpus corpus,
			List<RevisionSharedContextAsset> sharedContextAssets) throws IOException {
		Set<String> claimedPaths = new HashSet<>();
		Set<Long> suppliedContextIds = new HashSet<>();
		for (RevisionSharedContextAsset asset : sharedContextAssets) {
			if (asset == null) {
				throw new IOException("Shared-context asset list contains null");
			}
			Path relativePath = asset.getRelativePath();
			validateClaimedRelativePath(relativePath, "shared-context asset", claimedPaths);
			Path file = root.resolve(relativePath).normalize();
			validateInsideRoot(root, file, "Shared-context asset");
			validateNonEmptyFile(file, "Shared-context asset");
			long contextId = asset.getSharedContext().getId();
			if (!suppliedContextIds.add(contextId)) {
				throw new IOException("Duplicate shared-context asset for context " + contextId);
			}
		}
		Set<Long> expectedContextIds = new HashSet<>();
		collectExpectedSharedContextIds(corpus.getRootNodes(), expectedContextIds);
		if (!suppliedContextIds.equals(expectedContextIds)) {
			throw new IOException("Shared-context assets do not match renderable questions. Expected "
					+ expectedContextIds + " but found " + suppliedContextIds);
		}
	}

	private void validateSubjectIndex(Path root) throws IOException {
		Path index = root.resolve("index.html");
		validateNonEmptyFile(index, "Subject index");
	}
}
