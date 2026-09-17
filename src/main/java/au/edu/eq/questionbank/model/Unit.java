package au.edu.eq.questionbank.model;

/**
 * A root unit in a syllabus-version curriculum hierarchy.
 */
public final class Unit extends CurriculumNode {

	/**
	 * Creates a curriculum unit.
	 *
	 * @param id              the positive persistent node identifier
	 * @param syllabusVersion the containing syllabus version
	 * @param code            the non-blank curriculum code
	 * @param name            the non-blank unit name
	 * @param displayOrder    the non-negative order among units
	 */
	public Unit(long id, SyllabusVersion syllabusVersion, String code, String name, int displayOrder) {
		super(id, syllabusVersion, null, code, name, CurriculumLevel.UNIT, displayOrder);
	}
}
