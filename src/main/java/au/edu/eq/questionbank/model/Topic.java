package au.edu.eq.questionbank.model;

/**
 * A curriculum topic belonging to a {@link Unit}.
 */
public final class Topic extends CurriculumNode {

	/**
	 * Creates a curriculum topic.
	 *
	 * @param id              the positive persistent node identifier
	 * @param syllabusVersion the containing syllabus version
	 * @param parent          the topic's unit parent
	 * @param code            the non-blank curriculum code
	 * @param name            the non-blank topic name
	 * @param displayOrder    the non-negative order among sibling topics
	 */
	public Topic(long id, SyllabusVersion syllabusVersion, Unit parent, String code, String name, int displayOrder) {
		super(id, syllabusVersion, parent, code, name, CurriculumLevel.TOPIC, displayOrder);
	}

	@Override
	public Unit getParent() {
		return (Unit) super.getParent();
	}
}
