package au.edu.eq.questionbank.model;

public final class Unit extends CurriculumNode {

	public Unit(long id, SyllabusVersion syllabusVersion, String code, String name, int displayOrder) {
		super(id, syllabusVersion, null, code, name, CurriculumLevel.UNIT, displayOrder);
	}

}
