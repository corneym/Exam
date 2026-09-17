package au.edu.eq.questionbank.model;

/**
 * A curriculum subtopic belonging to a {@link Topic}.
 */
public final class Subtopic extends CurriculumNode {

	/**
	 * Creates a curriculum subtopic.
	 *
	 * @param id              the positive persistent node identifier
	 * @param syllabusVersion the containing syllabus version
	 * @param parent          the subtopic's topic parent
	 * @param code            the non-blank curriculum code
	 * @param name            the non-blank subtopic name
	 * @param displayOrder    the non-negative order among sibling subtopics
	 */
	public Subtopic(long id, SyllabusVersion syllabusVersion, Topic parent, String code, String name,
			int displayOrder) {
		super(id, syllabusVersion, parent, code, name, CurriculumLevel.SUBTOPIC, displayOrder);
	}

	@Override
	public Topic getParent() {
		return (Topic) super.getParent();
	}
}
