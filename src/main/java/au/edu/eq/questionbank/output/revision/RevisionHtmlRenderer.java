package au.edu.eq.questionbank.output.revision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SharedQuestionContext;
import au.edu.eq.questionbank.service.revision.RevisionCorpus;
import au.edu.eq.questionbank.service.revision.RevisionCorpusNode;
import au.edu.eq.questionbank.service.revision.RevisionGroupingMode;
import au.edu.eq.questionbank.service.revision.RevisionPresentationNode;
import au.edu.eq.questionbank.service.revision.RevisionPresentationPlan;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPlacement;
import au.edu.eq.questionbank.service.revision.RevisionQuestionPresentation;

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

			.question-type-nav {
			    display: flex;
			    flex-wrap: wrap;
			    gap: 0.75rem;
			    margin: 1rem 0 2rem;
			}

			.question-type-nav a {
			    padding: 0.5rem 0.75rem;
			    border: 1px solid #c7c9cc;
			    border-radius: 0.35rem;
			    background: #ffffff;
			    font-weight: 700;
			}

			.question-type-section {
			    margin: 2rem 0;
			    scroll-margin-top: 1rem;
			}

			.question-type-section > h2 {
			    margin-bottom: 1.25rem;
			}

			.question-metadata {
			    margin-top: 1rem;
			    padding-top: 0.75rem;
			    border-top: 1px solid #eceff1;
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

			.question-member + .question-member {
			    margin-top: 1.5rem;
			    padding-top: 1rem;
			    border-top: 1px solid #dadce0;
			}
			.marks {
			    white-space: nowrap;
			    font-weight: 700;
			}

			.shared-context-image,
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
	private final RevisionPresentationPlan presentationPlan;
	private final Map<Long, RevisionQuestionAsset> questionAssetsByQuestionId;
	private final Map<Long, List<RevisionAnswerAsset>> answerAssetsByQuestionId;
	private final Map<Long, RevisionSharedContextAsset> sharedContextAssetsByContextId;
	private final Map<Long, RevisionPresentationNode> presentationNodesByCurriculumNodeId;
	private final Map<Long, List<CurriculumNode>> currentDescriptorsByQuestionId;

	/**
	 * Indexes the presentation plan and rendered assets for subsequent HTML
	 * generation.
	 *
	 * @param presentationPlan    explicit student-facing presentation plan
	 * @param questionAssets      rendered question-body images
	 * @param answerAssets        rendered answer-region images
	 * @param sharedContextAssets rendered shared-context images
	 */
	public RevisionHtmlRenderer(RevisionPresentationPlan presentationPlan, List<RevisionQuestionAsset> questionAssets,
			List<RevisionAnswerAsset> answerAssets, List<RevisionSharedContextAsset> sharedContextAssets) {
		if (presentationPlan == null) {
			throw new NullPointerException("presentationPlan");
		}
		if (questionAssets == null) {
			throw new NullPointerException("questionAssets");
		}
		if (answerAssets == null) {
			throw new NullPointerException("answerAssets");
		}
		if (sharedContextAssets == null) {
			throw new NullPointerException("sharedContextAssets");
		}
		this.presentationPlan = presentationPlan;
		questionAssetsByQuestionId = indexQuestionAssets(questionAssets);
		answerAssetsByQuestionId = indexAnswerAssets(answerAssets);
		sharedContextAssetsByContextId = indexSharedContextAssets(sharedContextAssets);
		presentationNodesByCurriculumNodeId = indexPresentationNodes(presentationPlan);
		currentDescriptorsByQuestionId = indexCurrentDescriptors(presentationPlan.getSourceCorpus());
	}

	/**
	 * Writes the subject, unit, topic and optional subtopic pages and shared
	 * stylesheet.
	 *
	 * @param corpus     the current-curriculum revision corpus
	 * @param outputRoot the destination for generated files
	 * @return generated HTML paths in traversal order
	 * @throws IOException if an output file cannot be written
	 */
	public List<Path> render(RevisionCorpus corpus, Path outputRoot) throws IOException {
		if (corpus == null) {
			throw new NullPointerException("corpus");
		}
		if (outputRoot == null) {
			throw new NullPointerException("outputRoot");
		}
		validatePresentationPlan(corpus);
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

			// Empty curriculum branches remain in the corpus but are not student-facing.
			if (countPresentations(unitNode) == 0) {
				continue;
			}
			Path unitIndex = normalizedOutputRoot.resolve(unitRelativePath(unitNode));
			renderUnitPage(corpus, unitNode, normalizedOutputRoot, unitIndex);
			htmlFiles.add(unitIndex);
			for (RevisionCorpusNode topicNode : unitNode.getChildren()) {
				if (topicNode.getCurriculumNode().getLevel() != CurriculumLevel.TOPIC) {
					throw new IllegalStateException("Unit corpus children must be Topic nodes");
				}
				if (countPresentations(topicNode) == 0) {
					continue;
				}
				Path topicFile = normalizedOutputRoot.resolve(topicRelativePath(unitNode, topicNode));
				renderTopicPage(corpus, unitNode, topicNode, normalizedOutputRoot, topicFile);
				htmlFiles.add(topicFile);
				for (RevisionCorpusNode child : topicNode.getChildren()) {
					if (child.getCurriculumNode().getLevel() != CurriculumLevel.SUBTOPIC) {
						continue;
					}
					if (countPresentations(child) == 0) {
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

				// Do not generate diagnostic empty pages.
				if (countPresentations(topicNode) == 0) {
					continue;
				}
				Path outputFile = normalizedOutputRoot.resolve(topicRelativePath(unitNode, topicNode));
				renderTopicPage(corpus, unitNode, topicNode, normalizedOutputRoot, outputFile);
				topicFiles.add(outputFile);
				for (RevisionCorpusNode child : topicNode.getChildren()) {
					if (child.getCurriculumNode().getLevel() != CurriculumLevel.SUBTOPIC) {
						continue;
					}
					if (countPresentations(child) == 0) {
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

	private void appendAnswerContent(StringBuilder html, RevisionQuestionPresentation presentation, Question question,
			int displayNumber, Path outputRoot, Path outputFile) {
		Answer answer = question.getAnswer();
		String answerText = answer.getAnswerText();
		if (answerText != null && !answerText.isBlank()) {
			html.append("""
					    <p class="answer-text">%s</p>
					""".formatted(escapeText(answerText)));
		}
		List<RevisionAnswerAsset> answerAssets = answerAssetsByQuestionId.getOrDefault(question.getId(), List.of());

		// Refuse to emit an incomplete Answer when persisted regions require images.
		if (answerAssets.size() != answer.getRegions().size()) {
			throw new IllegalStateException("Expected " + answer.getRegions().size() + " answer assets for question "
					+ question.getId() + " but found " + answerAssets.size());
		}
		for (RevisionAnswerAsset answerAsset : answerAssets) {
			String source = relativeUrl(outputFile, outputRoot, answerAsset.getRelativePath());
			String alt;
			if (presentation.isMultipart()) {
				alt = "Answer for Question " + displayNumber + ", original Question " + question.getQuestionCode()
						+ ", region " + answerAsset.getRegionNumber();
			} else {
				alt = "Answer for Question " + displayNumber + ", region " + answerAsset.getRegionNumber();
			}
			html.append("""
					    <img class="answer-image" src="%s" alt="%s">
					""".formatted(source, escapeAttribute(alt)));
		}
	}

	private void appendDescriptorPresentationsOfType(StringBuilder html, RevisionCorpusNode descriptorNode,
			QuestionResponseType responseType, PageQuestionNumberSequence numbers, Path outputRoot, Path outputFile) {
		List<RevisionQuestionPresentation> matching = presentationsOfType(descriptorNode, responseType);
		if (matching.isEmpty()) {
			return;
		}
		html.append("""
				<section class="curriculum-section descriptor">
				    <h3>%s</h3>
				""".formatted(escapeText(nodeLabel(descriptorNode.getCurriculumNode()))));
		for (RevisionQuestionPresentation presentation : matching) {
			appendQuestionPresentation(html, presentation, numbers.next(), outputRoot, outputFile);
		}
		html.append("""
				</section>
				""");
	}

	private void appendPresentationAnswer(StringBuilder html, RevisionQuestionPresentation presentation,
			int displayNumber, Path outputRoot, Path outputFile) {
		boolean anyAnswer = false;
		for (Question member : presentation.getMembers()) {
			if (member.hasAnswer()) {
				anyAnswer = true;
				break;
			}
		}
		if (!anyAnswer) {
			html.append("""
					<p class="answer-unavailable">Answer not yet available.</p>
					""");
			return;
		}
		html.append("""
				<details class="answer">
				    <summary>Reveal answer</summary>
				    <div class="answer-content">
				""");
		for (Question member : presentation.getMembers()) {
			if (!member.hasAnswer()) {
				html.append("""
						    <p class="answer-unavailable">Answer not yet available.</p>
						""");
				continue;
			}
			appendAnswerContent(html, presentation, member, displayNumber, outputRoot, outputFile);
		}
		html.append("""
				    </div>
				</details>
				""");
	}

	private void appendPresentationMetadata(StringBuilder html, RevisionQuestionPresentation presentation) {
		html.append("""
				<div class="question-metadata">
				    <p class="source">%s</p>
				""".formatted(escapeText(sourceText(presentation))));
		LinkedHashMap<Long, CurriculumNode> descriptors = new LinkedHashMap<>();
		for (Question member : presentation.getMembers()) {
			for (CurriculumNode descriptor : currentDescriptorsByQuestionId.getOrDefault(member.getId(), List.of())) {
				descriptors.putIfAbsent(descriptor.getId(), descriptor);
			}
		}
		if (descriptors.size() == 1) {
			CurriculumNode descriptor = descriptors.values().iterator().next();
			html.append("""
					    <p class="provenance">Current descriptor: %s</p>
					""".formatted(escapeText(nodeLabel(descriptor))));
		} else if (descriptors.size() > 1) {
			List<String> labels = new ArrayList<>();
			for (CurriculumNode descriptor : descriptors.values()) {
				labels.add(nodeLabel(descriptor));
			}
			html.append("""
					    <p class="provenance">Current descriptors: %s</p>
					""".formatted(escapeText(String.join("; ", labels))));
		}
		html.append("""
				</div>
				""");
	}

	private void appendPresentationsOfType(StringBuilder html, RevisionCorpusNode node,
			QuestionResponseType responseType, PageQuestionNumberSequence numbers, Path outputRoot, Path outputFile) {
		for (RevisionQuestionPresentation presentation : presentationsOfType(node, responseType)) {
			appendQuestionPresentation(html, presentation, numbers.next(), outputRoot, outputFile);
		}
	}

	private void appendQuestionMember(StringBuilder html, RevisionQuestionPresentation presentation, Question question,
			int displayNumber, Path outputRoot, Path outputFile) {
		RevisionQuestionAsset questionAsset = questionAssetsByQuestionId.get(question.getId());
		if (questionAsset == null) {
			throw new IllegalStateException(
					"No question asset was supplied for renderable question " + question.getId());
		}
		String questionImageSource = relativeUrl(outputFile, outputRoot, questionAsset.getRelativePath());
		if (presentation.isMultipart()) {
			html.append("""
					<section class="question-member">
					""");
		}
		html.append("""
				    <img class="question-image" src="%s" alt="Question %d">
				""".formatted(questionImageSource, displayNumber));
		if (presentation.isMultipart()) {
			html.append("""
					</section>
					""");
		}
	}

	private void appendQuestionPresentation(StringBuilder html, RevisionQuestionPresentation presentation,
			int displayNumber, Path outputRoot, Path outputFile) {
		String markLabel = presentation.getTotalMarks() == 1 ? "1 mark" : presentation.getTotalMarks() + " marks";
		html.append("""
				<article class="question-card" id="question-%d">
				    <div class="question-heading">
				        <p class="question-number">Question %d</p>
				        <span class="marks">%s</span>
				    </div>
				""".formatted(displayNumber, displayNumber, escapeText(markLabel)));

		// Shared context remains immediately before the Question material it supports.
		appendSharedContext(html, presentation, displayNumber, outputRoot, outputFile);
		for (Question member : presentation.getMembers()) {
			appendQuestionMember(html, presentation, member, displayNumber, outputRoot, outputFile);
		}
		appendPresentationAnswer(html, presentation, displayNumber, outputRoot, outputFile);

		// Source and current-curriculum attribution belong after the Question and
		// Answer presentation rather than interrupting multipart material.
		appendPresentationMetadata(html, presentation);
		html.append("""
				</article>
				""");
	}

	private int appendQuestionTypeSections(StringBuilder html, List<RevisionCorpusNode> bucketNodes,
			boolean showDescriptorHeadings, Path outputRoot, Path outputFile) {
		List<QuestionResponseType> visibleTypes = new ArrayList<>();
		for (QuestionResponseType responseType : List.of(QuestionResponseType.MULTIPLE_CHOICE,
				QuestionResponseType.WRITTEN_RESPONSE, QuestionResponseType.UNKNOWN)) {
			if (hasPresentationsOfType(bucketNodes, responseType)) {
				visibleTypes.add(responseType);
			}
		}
		if (visibleTypes.size() > 1) {
			html.append("""
					<nav class="question-type-nav" aria-label="Question types">
					""");
			for (QuestionResponseType responseType : visibleTypes) {
				html.append("""
						    <a href="#%s">%s</a>
						""".formatted(questionTypeAnchor(responseType), escapeText(questionTypeLabel(responseType))));
			}
			html.append("""
					</nav>
					""");
		}
		PageQuestionNumberSequence numbers = new PageQuestionNumberSequence();
		for (QuestionResponseType responseType : visibleTypes) {
			html.append("""
					<section class="question-type-section">
					    <h2 id="%s">%s</h2>
					""".formatted(questionTypeAnchor(responseType), escapeText(questionTypeLabel(responseType))));
			if (showDescriptorHeadings) {
				for (RevisionCorpusNode descriptorNode : bucketNodes) {
					appendDescriptorPresentationsOfType(html, descriptorNode, responseType, numbers, outputRoot,
							outputFile);
				}
			} else {
				for (RevisionCorpusNode bucketNode : bucketNodes) {
					appendPresentationsOfType(html, bucketNode, responseType, numbers, outputRoot, outputFile);
				}
			}
			html.append("""
					</section>
					""");
		}
		return numbers.count();
	}

	private void appendSharedContext(StringBuilder html, RevisionQuestionPresentation presentation, int displayNumber,
			Path outputRoot, Path outputFile) {
		if (!presentation.shouldRenderSharedContext()) {
			return;
		}
		SharedQuestionContext context = presentation.getSharedContext();
		RevisionSharedContextAsset contextAsset = sharedContextAssetsByContextId.get(context.getId());
		if (contextAsset == null) {
			throw new IllegalStateException("No shared-context asset was supplied for context " + context.getId());
		}
		String source = relativeUrl(outputFile, outputRoot, contextAsset.getRelativePath());
		html.append("""
				    <img class="shared-context-image" src="%s" alt="Shared context for Question %d">
				""".formatted(source, displayNumber));
	}

	private void appendTopicContent(StringBuilder html, RevisionCorpusNode unitNode, RevisionCorpusNode topicNode,
			Path outputRoot, Path outputFile) {
		List<RevisionCorpusNode> children = topicNode.getChildren();
		if (children.isEmpty()) {
			html.append("""
					   <p class="empty-state">No revision questions available for this topic yet.</p>
					""");
			return;
		}
		CurriculumLevel childLevel = children.getFirst().getCurriculumNode().getLevel();
		for (RevisionCorpusNode child : children) {
			if (child.getCurriculumNode().getLevel() != childLevel) {
				throw new IllegalStateException("Topic corpus children must not mix Subtopic and Descriptor nodes");
			}
		}
		if (childLevel == CurriculumLevel.SUBTOPIC) {
			html.append("""
					   <h2>Subtopics</h2>
					   <ul class="navigation-list">
					""");
			for (RevisionCorpusNode subtopicNode : children) {
				int questionCount = countPresentations(subtopicNode);

				// Suppress empty Subtopics from student navigation.
				if (questionCount == 0) {
					continue;
				}
				String href = relativeUrl(outputFile, outputRoot,
						subtopicRelativePath(unitNode, topicNode, subtopicNode));
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
			return;
		}
		if (childLevel != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalStateException("Topic corpus children must be Subtopic or Descriptor nodes");
		}
		int renderedQuestions;
		if (presentationPlan.getGroupingMode() == RevisionGroupingMode.SUBTOPIC) {

			// Three-level curricula use the Topic as the practical roll-up page.
			renderedQuestions = appendQuestionTypeSections(html, List.of(topicNode), false, outputRoot, outputFile);
		} else {
			renderedQuestions = appendQuestionTypeSections(html, children, true, outputRoot, outputFile);
		}
		if (renderedQuestions == 0) {
			html.append("""
					   <p class="empty-state">No revision questions available for this topic yet.</p>
					""");
		}
	}

	private int countPresentations(RevisionCorpusNode node) {

		// Navigation counts student-facing cards; a multipart group contributes one
		// card.
		int count = requirePresentationNode(node).getPresentations().size();
		for (RevisionCorpusNode child : node.getChildren()) {
			count += countPresentations(child);
		}
		return count;
	}

	private int countStudentFacingPresentations(RevisionCorpus corpus) {
		int count = 0;
		for (RevisionCorpusNode unitNode : corpus.getRootNodes()) {
			count += countPresentations(unitNode);
		}
		return count;
	}

	private String escapeAttribute(String value) {
		return escapeText(value).replace("\"", "&quot;").replace("'", "&#39;");
	}

	private String escapeText(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private boolean hasPresentationsOfType(List<RevisionCorpusNode> nodes, QuestionResponseType responseType) {
		for (RevisionCorpusNode node : nodes) {
			if (!presentationsOfType(node, responseType).isEmpty()) {
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

			// Restore persisted region order and reject both gaps and duplicate sequence
			// numbers.
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

	private Map<Long, List<CurriculumNode>> indexCurrentDescriptors(RevisionCorpus corpus) {
		Map<Long, LinkedHashMap<Long, CurriculumNode>> mutableIndex = new LinkedHashMap<>();
		for (RevisionCorpusNode root : corpus.getRootNodes()) {
			indexCurrentDescriptors(root, mutableIndex);
		}
		Map<Long, List<CurriculumNode>> result = new LinkedHashMap<>();
		for (Map.Entry<Long, LinkedHashMap<Long, CurriculumNode>> entry : mutableIndex.entrySet()) {
			result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
		}
		return Map.copyOf(result);
	}

	private void indexCurrentDescriptors(RevisionCorpusNode node,
			Map<Long, LinkedHashMap<Long, CurriculumNode>> index) {
		if (node.getCurriculumNode().getLevel() == CurriculumLevel.DESCRIPTOR) {
			CurriculumNode descriptor = node.getCurriculumNode();
			for (RevisionQuestionPlacement placement : node.getQuestionPlacements()) {
				index.computeIfAbsent(placement.getQuestion().getId(), _ -> new LinkedHashMap<>())
						.putIfAbsent(descriptor.getId(), descriptor);
			}
		}
		for (RevisionCorpusNode child : node.getChildren()) {
			indexCurrentDescriptors(child, index);
		}
	}

	private void indexPresentationNode(RevisionPresentationNode node, Map<Long, RevisionPresentationNode> indexed) {
		long curriculumNodeId = node.getCurriculumNode().getId();
		if (indexed.putIfAbsent(curriculumNodeId, node) != null) {
			throw new IllegalArgumentException("Duplicate presentation curriculum node " + curriculumNodeId);
		}
		for (RevisionPresentationNode child : node.getChildren()) {
			indexPresentationNode(child, indexed);
		}
	}

	private Map<Long, RevisionPresentationNode> indexPresentationNodes(RevisionPresentationPlan plan) {
		Map<Long, RevisionPresentationNode> indexed = new HashMap<>();
		for (RevisionPresentationNode rootNode : plan.getRootNodes()) {
			indexPresentationNode(rootNode, indexed);
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

	private Map<Long, RevisionSharedContextAsset> indexSharedContextAssets(List<RevisionSharedContextAsset> assets) {
		Map<Long, RevisionSharedContextAsset> indexed = new HashMap<>();
		for (RevisionSharedContextAsset asset : assets) {
			if (asset == null) {
				throw new NullPointerException("sharedContextAssets contains null");
			}
			long contextId = asset.getSharedContext().getId();
			if (indexed.putIfAbsent(contextId, asset) != null) {
				throw new IllegalArgumentException("Duplicate shared-context asset for context " + contextId);
			}
		}
		return indexed;
	}

	private String nodeLabel(CurriculumNode node) {
		return node.getCode() + " " + node.getName();
	}

	private QuestionResponseType presentationResponseType(RevisionQuestionPresentation presentation) {
		boolean allMultipleChoice = true;
		boolean hasWrittenResponse = false;
		for (Question question : presentation.getMembers()) {
			QuestionResponseType responseType = question.getResponseType();
			if (responseType != QuestionResponseType.MULTIPLE_CHOICE) {
				allMultipleChoice = false;
			}
			if (responseType == QuestionResponseType.WRITTEN_RESPONSE) {
				hasWrittenResponse = true;
			}
		}
		if (allMultipleChoice) {
			return QuestionResponseType.MULTIPLE_CHOICE;
		}
		if (hasWrittenResponse) {
			return QuestionResponseType.WRITTEN_RESPONSE;
		}
		return QuestionResponseType.UNKNOWN;
	}

	private List<RevisionQuestionPresentation> presentationsOfType(RevisionCorpusNode node,
			QuestionResponseType responseType) {
		List<RevisionQuestionPresentation> result = new ArrayList<>();
		for (RevisionQuestionPresentation presentation : requirePresentationNode(node).getPresentations()) {
			if (presentationResponseType(presentation) == responseType) {
				result.add(presentation);
			}
		}
		return List.copyOf(result);
	}

	private String questionCountLabel(int count) {
		if (count == 1) {
			return "1 revision question";
		}
		return count + " revision questions";
	}

	private String questionTypeAnchor(QuestionResponseType responseType) {
		return switch (responseType) {
		case MULTIPLE_CHOICE -> "multiple-choice";
		case WRITTEN_RESPONSE -> "written-response";
		case UNKNOWN -> "other-questions";
		};
	}

	private String questionTypeLabel(QuestionResponseType responseType) {
		return switch (responseType) {
		case MULTIPLE_CHOICE -> "Multiple choice";
		case WRITTEN_RESPONSE -> "Written response";
		case UNKNOWN -> "Other questions";
		};
	}

	private String relativeUrl(Path outputFile, Path outputRoot, Path targetRelativePath) {
		Path target = outputRoot.resolve(targetRelativePath).normalize();
		if (!target.startsWith(outputRoot)) {
			throw new IllegalStateException("HTML asset reference escapes the export root");
		}

		// Rebase links for the current page depth, then escape them for an HTML
		// attribute.
		Path relative = outputFile.getParent().relativize(target);
		return escapeAttribute(relative.toString().replace('\\', '/'));
	}

	private void renderSubjectIndex(RevisionCorpus corpus, Path outputRoot, Path outputFile) throws IOException {
		Files.createDirectories(outputFile.getParent());
		String stylesheetSource = relativeUrl(outputFile, outputRoot, Path.of("assets", "revision.css"));
		int revisionQuestionCount = countStudentFacingPresentations(corpus);
		String revisionQuestionLabel = revisionQuestionCount == 1 ? "Revision question" : "Revision questions";
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

				    <section class="statistics" aria-label="Revision summary">
				        <p class="statistic">
				            <strong>%d</strong>
				            %s
				        </p>
				    </section>

				    <h2>Units</h2>
				    <ul class="navigation-list">
				""".formatted(escapeText(corpus.getSubject().getName()), stylesheetSource,
				escapeText(corpus.getSyllabusVersion().getName()), escapeText(corpus.getSubject().getName()),
				revisionQuestionCount, escapeText(revisionQuestionLabel)));
		for (RevisionCorpusNode unitNode : corpus.getRootNodes()) {
			int questionCount = countPresentations(unitNode);

			// The student navigation exposes only Units containing revision material.
			if (questionCount == 0) {
				continue;
			}
			String href = relativeUrl(outputFile, outputRoot, unitRelativePath(unitNode));
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
		int renderedQuestions;
		if (presentationPlan.getGroupingMode() == RevisionGroupingMode.SUBTOPIC) {
			renderedQuestions = appendQuestionTypeSections(html, List.of(subtopicNode), false, outputRoot, outputFile);
		} else {
			for (RevisionCorpusNode descriptorNode : subtopicNode.getChildren()) {
				if (descriptorNode.getCurriculumNode().getLevel() != CurriculumLevel.DESCRIPTOR) {
					throw new IllegalStateException("Subtopic corpus children must be Descriptor nodes");
				}
			}
			renderedQuestions = appendQuestionTypeSections(html, subtopicNode.getChildren(), true, outputRoot,
					outputFile);
		}
		if (renderedQuestions == 0) {
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
		appendTopicContent(html, unitNode, topicNode, outputRoot, outputFile);
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
			int questionCount = countPresentations(topicNode);

			// Empty Topics are neither linked nor generated.
			if (questionCount == 0) {
				continue;
			}
			String href = relativeUrl(outputFile, outputRoot, topicRelativePath(unitNode, topicNode));
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

	private RevisionPresentationNode requirePresentationNode(RevisionCorpusNode corpusNode) {
		RevisionPresentationNode node = presentationNodesByCurriculumNodeId.get(corpusNode.getCurriculumNode().getId());
		if (node == null) {
			throw new IllegalStateException(
					"Presentation plan is missing curriculum node " + corpusNode.getCurriculumNode().getId());
		}
		return node;
	}

	private String sourceText(RevisionQuestionPresentation presentation) {
		Question first = presentation.getMembers().getFirst();
		Exam exam = first.getExam();
		List<String> questionCodes = new ArrayList<>();
		for (Question member : presentation.getMembers()) {
			questionCodes.add(member.getQuestionCode());
		}
		String questionLabel;
		if (questionCodes.size() == 1) {
			questionLabel = "Question " + questionCodes.getFirst();
		} else {
			questionLabel = "Questions " + String.join(", ", questionCodes);
		}
		return "Source: " + exam.getProvider().getName() + ", " + exam.getYear() + ", " + exam.getName() + ", "
				+ first.getBooklet().getName() + ", " + questionLabel;
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

	private void validatePresentationPlan(RevisionCorpus corpus) {
		if (presentationPlan.getSubject().getId() != corpus.getSubject().getId()) {
			throw new IllegalArgumentException("Presentation plan belongs to another subject");
		}
		if (presentationPlan.getSyllabusVersion().getId() != corpus.getSyllabusVersion().getId()) {
			throw new IllegalArgumentException("Presentation plan belongs to another syllabus");
		}
	}

	private void writeStylesheet(Path outputRoot) throws IOException {
		Path stylesheet = outputRoot.resolve(Path.of("assets", "revision.css"));
		Files.createDirectories(stylesheet.getParent());
		Files.writeString(stylesheet, STYLESHEET);
	}

	private static final class PageQuestionNumberSequence {

		private int nextNumber = 1;

		private int count() {
			return nextNumber - 1;
		}

		private int next() {
			return nextNumber++;
		}
	}
}
