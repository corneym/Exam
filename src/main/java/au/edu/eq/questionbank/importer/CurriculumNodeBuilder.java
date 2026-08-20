package au.edu.eq.questionbank.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.SyllabusVersion;

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

		List<CurriculumNode> nodes = new ArrayList<>();

		Map<String, CurriculumNode> units = new LinkedHashMap<>();
		Map<String, CurriculumNode> topics = new LinkedHashMap<>();
		Map<String, CurriculumNode> subtopics = new LinkedHashMap<>();

		Map<String, Integer> nextTopicOrder = new HashMap<>();
		Map<String, Integer> nextSubtopicOrder = new HashMap<>();

		int nextUnitOrder = 1;

		for (CurriculumImportRow row : rows) {
			String[] parts = row.classificationCode().split("\\.");

			if (parts.length != 3) {
				throw new IllegalArgumentException(
						"Expected three-part classification code but found: " + row.classificationCode());
			}

			String unitCode = parts[0];
			String topicCode = parts[0] + "." + parts[1];
			String subtopicCode = row.classificationCode();

			CurriculumNode unit = units.get(unitCode);

			if (unit == null) {
				unit = new CurriculumNode(idSupplier.getAsLong(), syllabusVersion, null, unitCode, row.unitName(),
						CurriculumLevel.UNIT, nextUnitOrder++);

				units.put(unitCode, unit);
				nodes.add(unit);
			} else {
				checkName(unit, row.unitName());
			}

			CurriculumNode topic = topics.get(topicCode);

			if (topic == null) {
				int order = nextTopicOrder.getOrDefault(unitCode, 1);

				topic = new CurriculumNode(idSupplier.getAsLong(), syllabusVersion, unit, topicCode, row.topicName(),
						CurriculumLevel.TOPIC, order);

				topics.put(topicCode, topic);
				nodes.add(topic);
				nextTopicOrder.put(unitCode, order + 1);
			} else {
				checkName(topic, row.topicName());
			}

			CurriculumNode subtopic = subtopics.get(subtopicCode);

			if (subtopic == null) {
				int order = nextSubtopicOrder.getOrDefault(topicCode, 1);

				subtopic = new CurriculumNode(idSupplier.getAsLong(), syllabusVersion, topic, subtopicCode,
						row.subtopicName(), CurriculumLevel.SUBTOPIC, order);

				subtopics.put(subtopicCode, subtopic);
				nodes.add(subtopic);
				nextSubtopicOrder.put(topicCode, order + 1);
			} else {
				checkName(subtopic, row.subtopicName());
			}
		}

		return List.copyOf(nodes);
	}

	private void checkName(CurriculumNode node, String importedName) {
		if (!node.getName().equals(importedName)) {
			throw new IllegalArgumentException("Classification code " + node.getCode() + " has conflicting names: \""
					+ node.getName() + "\" and \"" + importedName + "\"");
		}
	}
}