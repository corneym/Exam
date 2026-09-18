package au.edu.eq.questionbank.service.curriculum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces a human-readable diagnostic representation of a transient curriculum
 * draft.
 * <p>
 * This is a preview/debugging format only. It is not an import format and is
 * not authoritative persistence.
 */
public final class CurriculumDraftTextExporter {

	/**
	 * Creates a text exporter for curriculum draft review.
	 */
	public CurriculumDraftTextExporter() {
	}

	/**
	 * Renders a curriculum draft as plain text.
	 *
	 * @param draft         draft to render
	 * @param sourcePdfPath source syllabus PDF, or {@code null} when not recorded
	 * @return human-readable draft text
	 * @throws NullPointerException if {@code draft} is {@code null}
	 */
	public String render(CurriculumDraft draft, Path sourcePdfPath) {
		if (draft == null) {
			throw new NullPointerException("draft");
		}
		StringBuilder output = new StringBuilder();
		output.append("CURRICULUM DRAFT\n");
		output.append("================\n\n");
		output.append("Source PDF: ");
		if (sourcePdfPath == null) {
			output.append("<not recorded>");
		} else {
			output.append(sourcePdfPath.toAbsolutePath().normalize());
		}
		output.append('\n');
		output.append("Node count: ").append(draft.nodes().size()).append("\n\n");
		appendValidation(output, draft.validationProblems());
		output.append("\nNODES\n");
		output.append("-----\n");
		if (draft.nodes().isEmpty()) {
			output.append("<none>\n");
			return output.toString();
		}
		Set<Long> visited = new HashSet<>();
		for (CurriculumDraftNode root : draft.childrenOf(null)) {
			appendNode(output, draft, root, 0, visited);
		}
		// Include unreachable nodes as well: a diagnostic preview must not hide invalid authored data.
		for (CurriculumDraftNode node : draft.nodes()) {
			if (!visited.contains(node.draftId())) {
				output.append("\n[UNREACHABLE OR INVALID HIERARCHY]\n");
				appendNode(output, draft, node, 0, visited);
			}
		}
		return output.toString();
	}

	/**
	 * Writes a rendered curriculum draft to a UTF-8 text file.
	 *
	 * @param draft         draft to write
	 * @param sourcePdfPath source syllabus PDF, or {@code null}
	 * @param target        output text file
	 * @throws IOException          if the file cannot be written
	 * @throws NullPointerException if {@code draft} or {@code target} is
	 *                              {@code null}
	 */
	public void write(CurriculumDraft draft, Path sourcePdfPath, Path target) throws IOException {
		if (target == null) {
			throw new NullPointerException("target");
		}
		Path normalisedTarget = target.toAbsolutePath().normalize();
		Path parent = normalisedTarget.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(normalisedTarget, render(draft, sourcePdfPath), StandardCharsets.UTF_8);
	}

	private void appendNode(StringBuilder output, CurriculumDraft draft, CurriculumDraftNode node, int depth,
			Set<Long> visited) {
		String indent = "  ".repeat(depth);
		if (!visited.add(node.draftId())) {
			output.append(indent).append("[cycle/reference already shown: draft ").append(node.draftId()).append("]\n");
			return;
		}
		output.append('\n');
		output.append(indent).append(node.level()).append(" [draftId=").append(node.draftId()).append("]\n");
		output.append(indent).append("  code: ").append(node.code()).append('\n');
		output.append(indent).append("  parentDraftId: ")
				.append(node.parentDraftId() == null ? "<none>" : node.parentDraftId()).append('\n');
		output.append(indent).append("  displayOrder: ").append(node.displayOrder()).append('\n');
		output.append(indent).append("  sourcePage: ")
				.append(node.sourcePageNumber() == null ? "<none>" : node.sourcePageNumber()).append('\n');
		output.append(indent).append("  text:\n");
		// Preserve trailing blank lines so the diagnostic text reflects the authored wording.
		String[] textLines = node.name().split("\\R", -1);
		for (String textLine : textLines) {
			output.append(indent).append("    ").append(textLine).append('\n');
		}
		for (CurriculumDraftNode child : draft.childrenOf(node.draftId())) {
			appendNode(output, draft, child, depth + 1, visited);
		}
	}

	private void appendValidation(StringBuilder output, List<String> problems) {
		if (problems.isEmpty()) {
			output.append("VALIDATION: VALID\n");
			return;
		}
		output.append("VALIDATION: ").append(problems.size())
				.append(problems.size() == 1 ? " problem\n" : " problems\n");
		for (String problem : problems) {
			output.append("  - ").append(problem).append('\n');
		}
	}
}
