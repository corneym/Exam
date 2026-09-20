package au.edu.eq.questionbank.model;

/**
 * Levels in the curriculum classification hierarchy, from a syllabus unit down
 * to its most specific descriptor level.
 */
public enum CurriculumLevel {
	/** Root grouping within a syllabus. */
	UNIT,
	/** Curriculum topic belonging to a unit. */
	TOPIC,
	/** Optional subdivision of a topic. */
	SUBTOPIC,
	/** Assessable curriculum statement under a topic or subtopic. */
	DESCRIPTOR
}
