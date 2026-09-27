package au.edu.eq.questionbank.ui.search;

import java.util.List;

import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * Resolved curriculum path used to present a selected Question's stored
 * classification in Search.
 *
 * @param classification    authoritative stored Question classification
 * @param unit              containing Unit
 * @param topic             containing Topic
 * @param subtopic          containing Subtopic, or {@code null} when a
 *                          Descriptor belongs directly to a Topic
 * @param descriptor        stored Descriptor, or {@code null} when the Question
 *                          is classified only to Subtopic level
 * @param descriptorChoices Descriptors available to the classification control
 */
record QuestionClassificationPath(CurriculumNode classification, CurriculumNode unit, CurriculumNode topic,
		CurriculumNode subtopic, CurriculumNode descriptor, List<CurriculumNode> descriptorChoices) {

	QuestionClassificationPath {

		// The UI must not observe later mutation of the list used to resolve the
		// selected Question's classification.
		descriptorChoices = List.copyOf(descriptorChoices);
	}

	boolean descriptorRefinementAvailable() {

		// Search may refine only a stored Subtopic classification that actually has
		// child Descriptors available.
		return descriptor == null && !descriptorChoices.isEmpty();
	}
}
