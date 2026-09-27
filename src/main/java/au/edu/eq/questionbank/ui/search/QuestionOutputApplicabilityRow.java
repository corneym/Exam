package au.edu.eq.questionbank.ui.search;

import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumNode;

/**
 * One current-curriculum placement shown in revision-output applicability.
 *
 * @param currentNode current Subtopic or Descriptor placement
 * @param excluded    whether revision output excludes this placement
 */
record QuestionOutputApplicabilityRow(CurriculumNode currentNode, boolean excluded) {

	QuestionOutputApplicabilityRow {
		if (currentNode == null) {
			throw new NullPointerException("currentNode");
		}

		// Revision-output rows represent only final Question-placement levels.
		if (currentNode.getLevel() != CurriculumLevel.SUBTOPIC
				&& currentNode.getLevel() != CurriculumLevel.DESCRIPTOR) {
			throw new IllegalArgumentException("Output applicability row requires a Subtopic or Descriptor");
		}
	}
}
