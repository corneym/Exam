package au.edu.eq.questionbank.model;

/**
 * A curriculum descriptor belonging either directly to a {@link Topic} or to
 * a {@link Subtopic}.
 */
public final class Descriptor extends CurriculumNode {

	/**
	 * Creates a descriptor beneath a subtopic.
	 *
	 * @param id the positive persistent node identifier
	 * @param syllabusVersion the containing syllabus version
	 * @param parent the descriptor's subtopic parent
	 * @param code the non-blank curriculum code
	 * @param text the non-blank descriptor text
	 * @param displayOrder the non-negative order among sibling descriptors
	 */
	public Descriptor(long id, SyllabusVersion syllabusVersion, Subtopic parent, String code, String text,
			int displayOrder) {

		super(id, syllabusVersion, parent, code, text, CurriculumLevel.DESCRIPTOR, displayOrder);
	}

	/**
	 * Creates a descriptor directly beneath a topic.
	 *
	 * @param id the positive persistent node identifier
	 * @param syllabusVersion the containing syllabus version
	 * @param parent the descriptor's topic parent
	 * @param code the non-blank curriculum code
	 * @param text the non-blank descriptor text
	 * @param displayOrder the non-negative order among sibling descriptors
	 */
	public Descriptor(long id, SyllabusVersion syllabusVersion, Topic parent, String code, String text,
			int displayOrder) {

		super(id, syllabusVersion, parent, code, text, CurriculumLevel.DESCRIPTOR, displayOrder);
	}
}
