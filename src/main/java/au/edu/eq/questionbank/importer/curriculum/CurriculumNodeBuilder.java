package au.edu.eq.questionbank.importer.curriculum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

/**
 * Builds a curriculum hierarchy from two-column curriculum import rows.
 */
public class CurriculumNodeBuilder {

	/**
	 * Creates a builder for converting imported rows into curriculum nodes.
	 */
	public CurriculumNodeBuilder() {
	}

	/**
	 * Builds nodes in hierarchy order: units, topics, then subtopics and
	 * descriptors. A three-part code with children becomes a subtopic; a
	 * childless three-part code becomes a descriptor directly beneath its topic.
	 * Four-part codes are descriptors beneath subtopics.
	 *
	 * @param syllabusVersion the version containing every generated node
	 * @param rows unique curriculum rows with complete parent chains
	 * @param idSupplier supplies a positive identifier for each generated node
	 * @return an immutable list ordered by hierarchy level and source-row order
	 *         within each level
	 * @throws NullPointerException if an argument or row element is {@code null}
	 * @throws IllegalArgumentException if a code is invalid or duplicated, a
	 *                                  parent row is absent, or a supplied model
	 *                                  value is invalid
	 */
	public List<CurriculumNode> build(SyllabusVersion syllabusVersion, List<CurriculumImportRow> rows,
			LongSupplier idSupplier) {
		validateArguments(syllabusVersion, rows, idSupplier);
		// Validate row codes and parent chains before consuming identifiers or constructing nodes.
		Map<String, CurriculumImportRow> rowsByCode = indexRows(rows);
		validateParents(rowsByCode);

		List<CurriculumNode> nodes = new ArrayList<>();
		Map<String, Unit> units = new HashMap<>();
		Map<String, Topic> topics = new HashMap<>();
		Map<String, Subtopic> subtopics = new HashMap<>();
		Map<String, Integer> nextTopicOrder = new HashMap<>();
		Map<String, Integer> nextSubtopicOrder = new HashMap<>();
		Map<String, Integer> nextDescriptorOrder = new HashMap<>();

		// Build parents first even when a workbook lists their children before them.
		addUnits(syllabusVersion, idSupplier, rowsByCode, units, nodes);
		addTopics(syllabusVersion, idSupplier, rowsByCode, units, topics, nextTopicOrder, nodes);
		addThreePartNodes(syllabusVersion, idSupplier, rowsByCode, topics, subtopics, nextSubtopicOrder,
				nextDescriptorOrder, nodes);
		addFourPartDescriptors(syllabusVersion, idSupplier, rowsByCode, subtopics, nextDescriptorOrder, nodes);

		return List.copyOf(nodes);
	}

	private void validateArguments(SyllabusVersion syllabusVersion, List<CurriculumImportRow> rows,
			LongSupplier idSupplier) {
		if (syllabusVersion == null) {
			throw new NullPointerException("syllabusVersion");
		}

		if (rows == null) {
			throw new NullPointerException("rows");
		}

		if (idSupplier == null) {
			throw new NullPointerException("idSupplier");
		}
	}

	private Map<String, CurriculumImportRow> indexRows(List<CurriculumImportRow> rows) {
		// Preserve source order rather than sorting by the numeric-looking codes.
		Map<String, CurriculumImportRow> rowsByCode = new LinkedHashMap<>();
		for (CurriculumImportRow row : rows) {
			if (row == null) {
				throw new NullPointerException("row");
			}
			validateCode(row.code());
			if (rowsByCode.containsKey(row.code())) {
				throw new IllegalArgumentException("Duplicate curriculum code: " + row.code());
			}
			rowsByCode.put(row.code(), row);
		}
		return rowsByCode;
	}

	private void addUnits(SyllabusVersion syllabusVersion, LongSupplier idSupplier,
			Map<String, CurriculumImportRow> rowsByCode, Map<String, Unit> units, List<CurriculumNode> nodes) {
		int nextUnitOrder = 1;
		for (CurriculumImportRow row : rowsByCode.values()) {
			if (depth(row.code()) != 1) {
				continue;
			}
			Unit unit = new Unit(idSupplier.getAsLong(), syllabusVersion, row.code(), row.content(), nextUnitOrder);
			nextUnitOrder++;
			units.put(row.code(), unit);
			nodes.add(unit);
		}
	}

	private void addTopics(SyllabusVersion syllabusVersion, LongSupplier idSupplier,
			Map<String, CurriculumImportRow> rowsByCode, Map<String, Unit> units, Map<String, Topic> topics,
			Map<String, Integer> nextTopicOrder, List<CurriculumNode> nodes) {
		for (CurriculumImportRow row : rowsByCode.values()) {
			if (depth(row.code()) != 2) {
				continue;
			}
			String unitCode = parentCode(row.code());
			Unit unit = units.get(unitCode);
			int order = nextOrder(nextTopicOrder, unitCode);
			Topic topic = new Topic(idSupplier.getAsLong(), syllabusVersion, unit, row.code(), row.content(), order);
			topics.put(row.code(), topic);
			nodes.add(topic);
		}
	}

	private void addThreePartNodes(SyllabusVersion syllabusVersion, LongSupplier idSupplier,
			Map<String, CurriculumImportRow> rowsByCode, Map<String, Topic> topics,
			Map<String, Subtopic> subtopics, Map<String, Integer> nextSubtopicOrder,
			Map<String, Integer> nextDescriptorOrder, List<CurriculumNode> nodes) {
		// A three-part node is a subtopic when it has children, otherwise a descriptor.
		for (CurriculumImportRow row : rowsByCode.values()) {
			if (depth(row.code()) != 3) {
				continue;
			}
			String topicCode = parentCode(row.code());
			Topic topic = topics.get(topicCode);
			if (hasChild(row.code(), rowsByCode)) {
				int order = nextOrder(nextSubtopicOrder, topicCode);
				Subtopic subtopic = new Subtopic(idSupplier.getAsLong(), syllabusVersion, topic, row.code(),
						row.content(), order);
				subtopics.put(row.code(), subtopic);
				nodes.add(subtopic);
			} else {
				// A leaf at this depth attaches directly to the topic, without a synthetic subtopic.
				int order = nextOrder(nextDescriptorOrder, topicCode);
				Descriptor descriptor = new Descriptor(idSupplier.getAsLong(), syllabusVersion, topic, row.code(),
						row.content(), order);
				nodes.add(descriptor);
			}
		}
	}

	private void addFourPartDescriptors(SyllabusVersion syllabusVersion, LongSupplier idSupplier,
			Map<String, CurriculumImportRow> rowsByCode, Map<String, Subtopic> subtopics,
			Map<String, Integer> nextDescriptorOrder, List<CurriculumNode> nodes) {
		for (CurriculumImportRow row : rowsByCode.values()) {
			if (depth(row.code()) != 4) {
				continue;
			}
			String subtopicCode = parentCode(row.code());
			Subtopic subtopic = subtopics.get(subtopicCode);
			int order = nextOrder(nextDescriptorOrder, subtopicCode);
			Descriptor descriptor = new Descriptor(idSupplier.getAsLong(), syllabusVersion, subtopic, row.code(),
					row.content(), order);
			nodes.add(descriptor);
		}
	}

	private int depth(String code) {
		return code.split("\\.").length;
	}

	private boolean hasChild(String code, Map<String, CurriculumImportRow> rowsByCode) {

		// Match a complete code component so, for example, 1.2.3 does not match 1.2.30.
		String childPrefix = code + ".";

		for (String possibleChild : rowsByCode.keySet()) {

			if (possibleChild.startsWith(childPrefix) && depth(possibleChild) == depth(code) + 1) {

				return true;
			}
		}

		return false;
	}

	private int nextOrder(Map<String, Integer> nextOrders, String parentCode) {

		// Each parent has its own one-based sequence, independent of gaps in imported codes.
		Integer next = nextOrders.get(parentCode);

		if (next == null) {
			next = 1;
		}

		nextOrders.put(parentCode, next + 1);

		return next;
	}

	private String parentCode(String code) {
		int lastDot = code.lastIndexOf('.');

		if (lastDot < 0) {
			return null;
		}

		return code.substring(0, lastDot);
	}

	private void validateCode(String code) {
		if (code == null || !code.matches("\\d+(\\.\\d+){0,3}")) {

			throw new IllegalArgumentException("Invalid curriculum code: " + code);
		}
	}

	private void validateParents(Map<String, CurriculumImportRow> rowsByCode) {

		// Requiring every immediate parent also ensures the whole chain back to a unit exists.
		for (CurriculumImportRow row : rowsByCode.values()) {
			int depth = depth(row.code());

			if (depth == 1) {
				continue;
			}

			String parentCode = parentCode(row.code());

			if (!rowsByCode.containsKey(parentCode)) {
				throw new IllegalArgumentException(
						"Missing parent " + parentCode + " for curriculum code " + row.code());
			}
		}
	}
}
