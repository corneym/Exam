package au.edu.eq.questionbank.importer;

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

	public List<CurriculumNode> build(SyllabusVersion syllabusVersion, List<CurriculumImportRow> rows,
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

		validateParents(rowsByCode);

		List<CurriculumNode> nodes = new ArrayList<>();

		Map<String, Unit> units = new HashMap<>();

		Map<String, Topic> topics = new HashMap<>();

		Map<String, Subtopic> subtopics = new HashMap<>();

		Map<String, Integer> nextTopicOrder = new HashMap<>();

		Map<String, Integer> nextSubtopicOrder = new HashMap<>();

		Map<String, Integer> nextDescriptorOrder = new HashMap<>();

		int nextUnitOrder = 1;

		/*
		 * Units first.
		 */
		for (CurriculumImportRow row : rowsByCode.values()) {
			if (depth(row.code()) != 1) {
				continue;
			}

			Unit unit = new Unit(idSupplier.getAsLong(), syllabusVersion, row.code(), row.content(), nextUnitOrder);

			nextUnitOrder++;

			units.put(row.code(), unit);
			nodes.add(unit);
		}

		/*
		 * Topics second.
		 */
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

		/*
		 * Three-part codes can be either subtopics or descriptors.
		 *
		 * If a three-part code has a child, it is a subtopic. Otherwise it is a
		 * descriptor belonging directly to the topic.
		 */
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
				int order = nextOrder(nextDescriptorOrder, topicCode);

				Descriptor descriptor = new Descriptor(idSupplier.getAsLong(), syllabusVersion, topic, row.code(),
						row.content(), order);

				nodes.add(descriptor);
			}
		}

		/*
		 * Four-part codes are always descriptors.
		 */
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

		return List.copyOf(nodes);
	}

	private int depth(String code) {
		return code.split("\\.").length;
	}

	private boolean hasChild(String code, Map<String, CurriculumImportRow> rowsByCode) {

		String childPrefix = code + ".";

		for (String possibleChild : rowsByCode.keySet()) {

			if (possibleChild.startsWith(childPrefix) && depth(possibleChild) == depth(code) + 1) {

				return true;
			}
		}

		return false;
	}

	private int nextOrder(Map<String, Integer> nextOrders, String parentCode) {

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