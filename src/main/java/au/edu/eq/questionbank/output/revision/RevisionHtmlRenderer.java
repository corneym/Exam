package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;

/**
 * Writes the student-facing static HTML pages for a revision corpus.
 */
public final class RevisionHtmlRenderer {

	private static final String STYLESHEET = """
			:root {
			    font-family: Arial, Helvetica, sans-serif;
			    line-height: 1.5;
			    color: #202124;
			    background: #f5f6f7;
			}

			* {
			    box-sizing: border-box;
			}

			body {
			    margin: 0;
			}

			.page {
			    width: min(100% - 2rem, 960px);
			    margin: 0 auto;
			    padding: 2rem 0 4rem;
			}

			.breadcrumbs {
			    margin-bottom: 1.5rem;
			    font-size: 0.9rem;
			    color: #5f6368;
			}

			.page-header {
			    margin-bottom: 2rem;
			}

			.eyebrow {
			    margin: 0 0 0.35rem;
			    font-size: 0.9rem;
			    font-weight: 700;
			    text-transform: uppercase;
			    letter-spacing: 0.04em;
			    color: #5f6368;
			}

			h1,
			h2,
			h3 {
			    line-height: 1.2;
			}

			.curriculum-section {
			    margin: 2rem 0;
			}

			.descriptor {
			    margin-top: 1.5rem;
			}

			.question-card {
			    margin: 1.25rem 0 2rem;
			    padding: 1.25rem;
			    border: 1px solid #c7c9cc;
			    border-radius: 0.5rem;
			    background: #ffffff;
			}

			.question-heading {
			    display: flex;
			    justify-content: space-between;
			    gap: 1rem;
			    align-items: baseline;
			    margin-bottom: 1rem;
			}

			.question-number {
			    margin: 0;
			    font-size: 1.1rem;
			    font-weight: 700;
			}

			.marks {
			    white-space: nowrap;
			    font-weight: 700;
			}

			.question-image,
			.answer-image {
			    display: block;
			    max-width: 100%;
			    height: auto;
			    margin: 1rem 0;
			}

			.source,
			.provenance {
			    margin: 0.5rem 0;
			    font-size: 0.875rem;
			    color: #5f6368;
			}

			.answer {
			    margin-top: 1.25rem;
			    border-top: 1px solid #dadce0;
			    padding-top: 1rem;
			}

			.answer summary {
			    width: fit-content;
			    padding: 0.55rem 0.8rem;
			    border: 1px solid #8a8d91;
			    border-radius: 0.35rem;
			    font-weight: 700;
			    cursor: pointer;
			    background: #f8f9fa;
			}

			.answer summary:hover {
			    background: #eceff1;
			}

			.answer summary:focus-visible {
			    outline: 3px solid #5f6368;
			    outline-offset: 2px;
			}

			.answer-content {
			    margin-top: 1rem;
			    padding: 1rem;
			    border-left: 4px solid #c7c9cc;
			    background: #f8f9fa;
			}

			.answer-text {
			    white-space: pre-wrap;
			}

			.answer-unavailable,
			.empty-state {
			    color: #5f6368;
			    font-style: italic;
			}

						.breadcrumbs a {
			    color: inherit;
			}

			.navigation-list {
			    list-style: none;
			    margin: 1.5rem 0;
			    padding: 0;
			}

			.navigation-list li {
			    margin: 0.75rem 0;
			    padding: 1rem;
			    border: 1px solid #dadce0;
			    border-radius: 0.4rem;
			    background: #ffffff;
			}

			.navigation-list a {
			    font-weight: 700;
			}

			.resource-count {
			    margin-left: 0.5rem;
			    color: #5f6368;
			    font-size: 0.9rem;
			}

			.statistics {
			    display: grid;
			    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
			    gap: 1rem;
			    margin: 1.5rem 0 2rem;
			}

			.statistic {
			    margin: 0;
			    padding: 1rem;
			    border: 1px solid #dadce0;
			    border-radius: 0.4rem;
			    background: #ffffff;
			}

			.statistic strong {
			    display: block;
			    font-size: 1.5rem;
			}

			@media (max-width: 600px) {
			    .page {
			        width: min(100% - 1rem, 960px);
			        padding-top: 1rem;
			    }

			    .question-card {
			        padding: 1rem;
			    }
			}
			""";
	private final Map<Long, RevisionQuestionAsset> questionAssetsByQuestionId;
	private final Map<Long, List<RevisionAnswerAsset>> answerAssetsByQuestionId;

	public RevisionHtmlRenderer(List<RevisionQuestionAsset> questionAssets, List<RevisionAnswerAsset> answerAssets) {
		if (questionAssets == null) {
			throw new NullPointerException("questionAssets");
		}
		if (answerAssets == null) {
			throw new NullPointerException("answerAssets");
		}
		questionAssetsByQuestionId = indexQuestionAssets(questionAssets);
		answerAssetsByQuestionId = indexAnswerAssets(answerAssets);
	}

	public List<Path> render(RevisionCorpus corpus, Path outputRoot) throws IOException {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (outputRoot == null) {
			throw new NullPointerException("outputRoot");
		}
		Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
		writeStylesheet(normalizedOutputRoot);
		List<Path> htmlFiles = new ArrayList<Path>();
		Path subjectIndex = normalizedOutputRoot.resolve("index.html");
		renderSubjectIndex(corpus, normalizedOutputRoot, subjectIndex);
		htmlFiles.add(subjectIndex);
		for (RevisionCorpusNode unitNode : corpus.getRootNodes()) {
			if (unitNode.getCurriculumNode().getLevel() != CurriculumLevel.UNIT) {
				throw new IllegalStateException("Corpus root nodes must be Unit nodes");
			}
			Path unitIndex = normalizedOutputRoot.resolve(unitRelativePath(unitNode));
			renderUnitPage(corpus, unitNode, normalizedOutputRoot, unitIndex);
			htmlFiles.add(unitIndex);
			for (RevisionCorpusNode topicNode : unitNode.getChildren()) {
				if (topicNode.getCurriculumNode().getLevel() != CurriculumLevel.TOPIC) {
					throw new IllegalStateException("Unit corpus children must be Topic nodes");
				}
				Path topicFile = normalizedOutputRoot.resolve(topicRelativePath(unitNode, topicNode));
				renderTopicPage(corpus, unitNode, topicNode, normalizedOutputRoot, topicFile);
				htmlFiles.add(topicFile);
				for (RevisionCorpusNode child : topicNode.getChildren()) {
					if (child.getCurriculumNode().getLevel() != CurriculumLevel.SUBTOPIC) {
						continue;
					}
					Path subtopicFile = normalizedOutputRoot.resolve(subtopicRelativePath(unitNode, topicNode, child));
					renderSubtopicPage(corpus, unitNode, topicNode, child, normalizedOutputRoot, subtopicFile);
					htmlFiles.add(subtopicFile);
				}
			}
		}
		return List.copyOf(htmlFiles);
	}

	/**
	 * Writes the shared stylesheet and pages for every Topic and Subtopic in the
	 * corpus.
	 *
	 * @param corpus     revision corpus
	 * @param outputRoot root directory of the static revision export
	 * @return Topic and Subtopic HTML files in curriculum order
	 * @throws IOException           if output cannot be written
	 * @throws IllegalStateException if a required rendered asset is missing
	 */
	public List<Path> renderTopicPages(RevisionCorpus corpus, Path outputRoot) throws IOException {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (outputRoot == null) {
			throw new NullPointerException("outputRoot");
		}
		Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
		writeStylesheet(normalizedOutputRoot);
		List<Path> topicFiles = new ArrayList<Path>();
		for (RevisionCorpusNode unitNode : corpus.getRootNodes()) {
			for (RevisionCorpusNode topicNode : unitNode.getChildren()) {
				if (topicNode.getCurriculumNode().getLevel() != CurriculumLevel.TOPIC) {
					throw new IllegalStateException("Unit corpus children must be Topic nodes");
				}
				Path outputFile = normalizedOutputRoot.resolve(topicRelativePath(unitNode, topicNode));
				renderTopicPage(corpus, unitNode, topicNode, normalizedOutputRoot, outputFile);
				topicFiles.add(outputFile);
				for (RevisionCorpusNode child : topicNode.getChildren()) {
					if (child.getCurriculumNode().getLevel() != CurriculumLevel.SUBTOPIC) {
						continue;
					}
					Path subtopicFile = normalizedOutputRoot.resolve(subtopicRelativePath(unitNode, topicNode, child));
					renderSubtopicPage(corpus, unitNode, topicNode, child, normalizedOutputRoot, subtopicFile);
					topicFiles.add(subtopicFile);
				}
			}
		}
		return List.copyOf(topicFiles);
	}

	private void appendAnswer(StringBuilder html, RevisionQuestionPlacement placement, Path outputRoot,
			Path outputFile) {
		Question question = placement.getQuestion();
		if (!question.hasAnswer()) {
			html.append("""
					<p class="answer-unavailable">Answer not yet available.</p>
					""");
			return;
		}
		Answer answer = question.getAnswer();
		html.append("""
				<details class="answer">
				    <summary>Reveal answer</summary>
				    <div class="answer-content">
				""");
		String answerText = answer.getAnswerText();
		if (answerText != null && !answerText.isBlank()) {
			html.append("""
					    <p class="answer-text">%s</p>
					""".formatted(escapeText(answerText)));
		}
		List<RevisionAnswerAsset> answerAssets = answerAssetsByQuestionId.getOrDefault(question.getId(), List.of());
		if (answerAssets.size() != answer.getRegions().size()) {
			throw new IllegalStateException("Expected " + answer.getRegions().size() + " answer assets for question "
					+ question.getId() + " but found " + answerAssets.size());
		}
		for (RevisionAnswerAsset answerAsset : answerAssets) {
			String source = relativeUrl(outputFile, outputRoot, answerAsset.getRelativePath());
			html.append("""
					    <img class="answer-image" src="%s" alt="Answer for Question %d, part %d">
					""".formatted(source, placement.getRevisionNumber(), answerAsset.getRegionNumber()));
		}
		html.append("""
				    </div>
				</details>
				""");
	}

	private void appendDescriptorSection(StringBuilder html, RevisionCorpusNode descriptorNode, int headingLevel,
			Path outputRoot, Path outputFile) {
		if (!hasRenderablePlacements(descriptorNode)) {
			return;
		}
		CurriculumNode descriptor = descriptorNode.getCurriculumNode();
		html.append("""
				<section class="curriculum-section descriptor">
				    <h%d>%s</h%d>
				""".formatted(headingLevel, escapeText(nodeLabel(descriptor)), headingLevel));
		appendQuestionPlacements(html, descriptorNode, outputRoot, outputFile);
		html.append("""
				</section>
				""");
	}

	private void appendQuestion(StringBuilder html, RevisionQuestionPlacement placement, Path outputRoot,
			Path outputFile) {
		if (!placement.isRenderable()) {
			return;
		}
		Question question = placement.getQuestion();
		RevisionQuestionAsset questionAsset = questionAssetsByQuestionId.get(question.getId());
		if (questionAsset == null) {
			throw new IllegalStateException(
					"No question asset was supplied for renderable question " + question.getId());
		}
		String questionImageSource = relativeUrl(outputFile, outputRoot, questionAsset.getRelativePath());
		String markLabel = question.getMarks() == 1 ? "1 mark" : question.getMarks() + " marks";
		Exam exam = question.getExam();
		String sourceText = "Source: " + exam.getProvider().getName() + ", " + exam.getYear() + ", " + exam.getName()
				+ ", " + question.getBooklet().getName() + ", Question " + question.getQuestionCode();
		CurriculumNode originalClassification = question.getClassification();
		String provenanceText = "Original classification: " + originalClassification.getSyllabusVersion().getName()
				+ " — " + originalClassification.getCode() + " " + originalClassification.getName();
		html.append("""
				<article class="question-card" id="question-%d">
				    <div class="question-heading">
				        <p class="question-number">Question %d</p>
				        <span class="marks">%s</span>
				    </div>
				    <img class="question-image" src="%s" alt="Question %d">
				    <p class="source">%s</p>
				    <p class="provenance">%s</p>
				""".formatted(placement.getRevisionNumber(), placement.getRevisionNumber(), escapeText(markLabel),
				questionImageSource, placement.getRevisionNumber(), escapeText(sourceText),
				escapeText(provenanceText)));
		appendAnswer(html, placement, outputRoot, outputFile);
		html.append("""
				</article>
				""");
	}

	private int appendQuestionPlacements(StringBuilder html, RevisionCorpusNode node, Path outputRoot,
			Path outputFile) {
		int renderedCount = 0;
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (!placement.isRenderable()) {
				continue;
			}
			appendQuestion(html, placement, outputRoot, outputFile);
			renderedCount++;
		}
		return renderedCount;
	}

	private int countRenderablePlacements(RevisionCorpusNode node) {
		int count = 0;
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (placement.isRenderable()) {
				count++;
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			count += countRenderablePlacements(child);
		}
		return count;
	}

	private String escapeAttribute(String value) {
		return escapeText(value).replace("\"", "&quot;").replace("'", "&#39;");
	}

	private String escapeText(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private boolean hasRenderablePlacements(RevisionCorpusNode node) {
		for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
			if (placement.isRenderable()) {
				return true;
			}
		}
		return false;
	}

	private Map<Long, List<RevisionAnswerAsset>> indexAnswerAssets(List<RevisionAnswerAsset> assets) {
		Map<Long, List<RevisionAnswerAsset>> indexed = new HashMap<Long, List<RevisionAnswerAsset>>();
		for (RevisionAnswerAsset asset : assets) {
			if (asset == null) {
				throw new NullPointerException("answerAssets contains null");
			}
			long questionId = asset.getQuestion().getId();
			List<RevisionAnswerAsset> questionAssets = indexed.get(questionId);
			if (questionAssets == null) {
				questionAssets = new ArrayList<RevisionAnswerAsset>();
				indexed.put(questionId, questionAssets);
			}
			questionAssets.add(asset);
		}
		for (List<RevisionAnswerAsset> questionAssets : indexed.values()) {
			questionAssets.sort(Comparator.comparingInt(RevisionAnswerAsset::getRegionNumber));
			for (int index = 0; index < questionAssets.size(); index++) {
				int expectedRegionNumber = index + 1;
				int actualRegionNumber = questionAssets.get(index).getRegionNumber();
				if (actualRegionNumber != expectedRegionNumber) {
					throw new IllegalArgumentException("Answer asset region numbers must be contiguous from 1");
				}
			}
		}
		return indexed;
	}

	private Map<Long, RevisionQuestionAsset> indexQuestionAssets(List<RevisionQuestionAsset> assets) {
		Map<Long, RevisionQuestionAsset> indexed = new HashMap<Long, RevisionQuestionAsset>();
		for (RevisionQuestionAsset asset : assets) {
			if (asset == null) {
				throw new NullPointerException("questionAssets contains null");
			}
			long questionId = asset.getQuestion().getId();
			if (indexed.putIfAbsent(questionId, asset) != null) {
				throw new IllegalArgumentException("Duplicate question asset for question " + questionId);
			}
		}
		return indexed;
	}

	private String nodeLabel(CurriculumNode node) {
		return node.getCode() + " " + node.getName();
	}

	private String questionCountLabel(int count) {
		if (count == 1) {
			return "1 revision question";
		}
		return count + " revision questions";
	}

	private String relativeUrl(Path outputFile, Path outputRoot, Path targetRelativePath) {
		Path target = outputRoot.resolve(targetRelativePath).normalize();
		if (!target.startsWith(outputRoot)) {
			throw new IllegalStateException("HTML asset reference escapes the export root");
		}
		Path relative = outputFile.getParent().relativize(target);
		return escapeAttribute(relative.toString().replace('\\', '/'));
	}

	private void renderSubjectIndex(RevisionCorpus corpus, Path outputRoot, Path outputFile) throws IOException {
		Files.createDirectories(outputFile.getParent());
		String stylesheetSource = relativeUrl(outputFile, outputRoot, Path.of("assets", "revision.css"));
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html lang="en">
				<head>
				    <meta charset="UTF-8">
				    <meta name="viewport" content="width=device-width, initial-scale=1">
				    <title>%s Revision</title>
				    <link rel="stylesheet" href="%s">
				</head>
				<body>
				<main class="page">
				    <header class="page-header">
				        <p class="eyebrow">%s syllabus</p>
				        <h1>%s Revision</h1>
				    </header>

				    <section class="statistics" aria-label="Revision corpus status">
				        <p class="statistic">
				            <strong>%d</strong>
				            Applicable questions
				        </p>
				        <p class="statistic">
				            <strong>%d</strong>
				            Exportable questions
				        </p>
				        <p class="statistic">
				            <strong>%d</strong>
				            Awaiting question capture
				        </p>
				    </section>

				    <h2>Units</h2>
				    <ul class="navigation-list">
				""".formatted(escapeText(corpus.getSubject().getName()), stylesheetSource,
				escapeText(corpus.getSyllabusVersion().getName()), escapeText(corpus.getSubject().getName()),
				corpus.getStatistics().getUniqueApplicableQuestions(), corpus.getStatistics().getRenderableQuestions(),
				corpus.getStatistics().getMissingQuestionRegionQuestions()));
		for (RevisionCorpusNode unitNode : corpus.getRootNodes()) {
			String href = relativeUrl(outputFile, outputRoot, unitRelativePath(unitNode));
			int questionCount = countRenderablePlacements(unitNode);
			html.append("""
					       <li>
					           <a href="%s">%s</a>
					           <span class="resource-count">%s</span>
					       </li>
					""".formatted(href, escapeText(nodeLabel(unitNode.getCurriculumNode())),
					escapeText(questionCountLabel(questionCount))));
		}
		html.append("""
				    </ul>
				</main>
				</body>
				</html>
				""");
		Files.writeString(outputFile, html.toString());
	}

	private void renderSubtopicPage(RevisionCorpus corpus, RevisionCorpusNode unitNode, RevisionCorpusNode topicNode,
			RevisionCorpusNode subtopicNode, Path outputRoot, Path outputFile) throws IOException {
		Files.createDirectories(outputFile.getParent());
		CurriculumNode unit = unitNode.getCurriculumNode();
		CurriculumNode topic = topicNode.getCurriculumNode();
		CurriculumNode subtopic = subtopicNode.getCurriculumNode();
		String stylesheetSource = relativeUrl(outputFile, outputRoot, Path.of("assets", "revision.css"));
		String subjectHref = relativeUrl(outputFile, outputRoot, Path.of("index.html"));
		String unitHref = relativeUrl(outputFile, outputRoot, unitRelativePath(unitNode));
		String topicHref = relativeUrl(outputFile, outputRoot, topicRelativePath(unitNode, topicNode));
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html lang="en">
				<head>
				    <meta charset="UTF-8">
				    <meta name="viewport" content="width=device-width, initial-scale=1">
				    <title>%s — %s Revision</title>
				    <link rel="stylesheet" href="%s">
				</head>
				<body>
				<main class="page">
				    <nav class="breadcrumbs" aria-label="Breadcrumb">
				        <a href="%s">%s</a>
				        ›
				        <a href="%s">%s</a>
				        ›
				        <a href="%s">%s</a>
				        ›
				        <span aria-current="page">%s</span>
				    </nav>

				    <header class="page-header">
				        <p class="eyebrow">%s · %s</p>
				        <h1>%s</h1>
				    </header>
				""".formatted(escapeText(subtopic.getName()), escapeText(corpus.getSubject().getName()),
				stylesheetSource, subjectHref, escapeText(corpus.getSubject().getName()), unitHref,
				escapeText(nodeLabel(unit)), topicHref, escapeText(nodeLabel(topic)), escapeText(nodeLabel(subtopic)),
				escapeText(corpus.getSubject().getName()), escapeText(corpus.getSyllabusVersion().getName()),
				escapeText(nodeLabel(subtopic))));
		appendQuestionPlacements(html, subtopicNode, outputRoot, outputFile);
		for (RevisionCorpusNode descriptorNode : subtopicNode.getChildren()) {
			if (descriptorNode.getCurriculumNode().getLevel() != CurriculumLevel.DESCRIPTOR) {
				throw new IllegalStateException("Subtopic corpus children must be Descriptor nodes");
			}
			appendDescriptorSection(html, descriptorNode, 2, outputRoot, outputFile);
		}
		if (countRenderablePlacements(subtopicNode) == 0) {
			html.append("""
					   <p class="empty-state">No revision questions available for this subtopic yet.</p>
					""");
		}
		html.append("""
				</main>
				</body>
				</html>
				""");
		Files.writeString(outputFile, html.toString());
	}

	private void renderTopicPage(RevisionCorpus corpus, RevisionCorpusNode unitNode, RevisionCorpusNode topicNode,
			Path outputRoot, Path outputFile) throws IOException {
		Files.createDirectories(outputFile.getParent());
		CurriculumNode unit = unitNode.getCurriculumNode();
		CurriculumNode topic = topicNode.getCurriculumNode();
		String stylesheetSource = relativeUrl(outputFile, outputRoot, Path.of("assets", "revision.css"));
		String subjectHref = relativeUrl(outputFile, outputRoot, Path.of("index.html"));
		String unitHref = relativeUrl(outputFile, outputRoot, unitRelativePath(unitNode));
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html lang="en">
				<head>
				    <meta charset="UTF-8">
				    <meta name="viewport" content="width=device-width, initial-scale=1">
				    <title>%s — %s Revision</title>
				    <link rel="stylesheet" href="%s">
				</head>
				<body>
				<main class="page">
				    				    <nav class="breadcrumbs" aria-label="Breadcrumb">
				        <a href="%s">%s</a>
				        ›
				        <a href="%s">%s</a>
				        ›
				        <span aria-current="page">%s</span>
				    </nav>
				    <header class="page-header">
				        <p class="eyebrow">%s · %s</p>
				        <h1>%s</h1>
				    </header>
				""".formatted(escapeText(topic.getName()), escapeText(corpus.getSubject().getName()), stylesheetSource,
				subjectHref, escapeText(corpus.getSubject().getName()), unitHref, escapeText(nodeLabel(unit)),
				escapeText(nodeLabel(topic)), escapeText(corpus.getSubject().getName()),
				escapeText(corpus.getSyllabusVersion().getName()), escapeText(nodeLabel(topic))));
		List<RevisionCorpusNode> children = topicNode.getChildren();
		if (children.isEmpty()) {
			html.append("""
					   <p class="empty-state">No revision questions available for this topic yet.</p>
					""");
		} else {
			CurriculumLevel childLevel = children.getFirst().getCurriculumNode().getLevel();
			if (childLevel == CurriculumLevel.SUBTOPIC) {
				html.append("""
						   <h2>Subtopics</h2>
						   <ul class="navigation-list">
						""");
				for (RevisionCorpusNode subtopicNode : children) {
					if (subtopicNode.getCurriculumNode().getLevel() != CurriculumLevel.SUBTOPIC) {
						throw new IllegalStateException(
								"Topic corpus children must not mix Subtopic and Descriptor nodes");
					}
					String href = relativeUrl(outputFile, outputRoot,
							subtopicRelativePath(unitNode, topicNode, subtopicNode));
					int questionCount = countRenderablePlacements(subtopicNode);
					html.append("""
							      <li>
							          <a href="%s">%s</a>
							          <span class="resource-count">%s</span>
							      </li>
							""".formatted(href, escapeText(nodeLabel(subtopicNode.getCurriculumNode())),
							escapeText(questionCountLabel(questionCount))));
				}
				html.append("""
						   </ul>
						""");
			} else if (childLevel == CurriculumLevel.DESCRIPTOR) {
				for (RevisionCorpusNode descriptorNode : children) {
					if (descriptorNode.getCurriculumNode().getLevel() != CurriculumLevel.DESCRIPTOR) {
						throw new IllegalStateException(
								"Topic corpus children must not mix Subtopic and Descriptor nodes");
					}
					appendDescriptorSection(html, descriptorNode, 2, outputRoot, outputFile);
				}
				if (countRenderablePlacements(topicNode) == 0) {
					html.append("""
							   <p class="empty-state">No revision questions available for this topic yet.</p>
							""");
				}
			} else {
				throw new IllegalStateException("Topic corpus children must be Subtopic or Descriptor nodes");
			}
		}
		html.append("""
				</main>
				</body>
				</html>
				""");
		Files.writeString(outputFile, html.toString());
	}

	private void renderUnitPage(RevisionCorpus corpus, RevisionCorpusNode unitNode, Path outputRoot, Path outputFile)
			throws IOException {
		Files.createDirectories(outputFile.getParent());
		CurriculumNode unit = unitNode.getCurriculumNode();
		String stylesheetSource = relativeUrl(outputFile, outputRoot, Path.of("assets", "revision.css"));
		String subjectHref = relativeUrl(outputFile, outputRoot, Path.of("index.html"));
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html lang="en">
				<head>
				    <meta charset="UTF-8">
				    <meta name="viewport" content="width=device-width, initial-scale=1">
				    <title>%s — %s Revision</title>
				    <link rel="stylesheet" href="%s">
				</head>
				<body>
				<main class="page">
				    <nav class="breadcrumbs" aria-label="Breadcrumb">
				        <a href="%s">%s</a>
				        ›
				        <span aria-current="page">%s</span>
				    </nav>

				    <header class="page-header">
				        <p class="eyebrow">%s · %s</p>
				        <h1>%s</h1>
				    </header>

				    <h2>Topics</h2>
				    <ul class="navigation-list">
				""".formatted(escapeText(unit.getName()), escapeText(corpus.getSubject().getName()), stylesheetSource,
				subjectHref, escapeText(corpus.getSubject().getName()), escapeText(nodeLabel(unit)),
				escapeText(corpus.getSubject().getName()), escapeText(corpus.getSyllabusVersion().getName()),
				escapeText(nodeLabel(unit))));
		for (RevisionCorpusNode topicNode : unitNode.getChildren()) {
			if (topicNode.getCurriculumNode().getLevel() != CurriculumLevel.TOPIC) {
				throw new IllegalStateException("Unit corpus children must be Topic nodes");
			}
			String href = relativeUrl(outputFile, outputRoot, topicRelativePath(unitNode, topicNode));
			int questionCount = countRenderablePlacements(topicNode);
			html.append("""
					       <li>
					           <a href="%s">%s</a>
					           <span class="resource-count">%s</span>
					       </li>
					""".formatted(href, escapeText(nodeLabel(topicNode.getCurriculumNode())),
					escapeText(questionCountLabel(questionCount))));
		}
		html.append("""
				    </ul>
				</main>
				</body>
				</html>
				""");
		Files.writeString(outputFile, html.toString());
	}

	private Path subtopicRelativePath(RevisionCorpusNode unitNode, RevisionCorpusNode topicNode,
			RevisionCorpusNode subtopicNode) {
		return Path.of("units", "unit-" + unitNode.getCurriculumNode().getId(),
				"topic-" + topicNode.getCurriculumNode().getId(),
				"subtopic-" + subtopicNode.getCurriculumNode().getId() + ".html");
	}

	private Path topicRelativePath(RevisionCorpusNode unitNode, RevisionCorpusNode topicNode) {
		return Path.of("units", "unit-" + unitNode.getCurriculumNode().getId(),
				"topic-" + topicNode.getCurriculumNode().getId() + ".html");
	}

	private Path unitRelativePath(RevisionCorpusNode unitNode) {
		return Path.of("units", "unit-" + unitNode.getCurriculumNode().getId(), "index.html");
	}

	private void writeStylesheet(Path outputRoot) throws IOException {
		Path stylesheet = outputRoot.resolve(Path.of("assets", "revision.css"));
		Files.createDirectories(stylesheet.getParent());
		Files.writeString(stylesheet, STYLESHEET);
	}
}
