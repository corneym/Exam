package au.edu.eq.questionbank.model;

public final class Topic extends CurriculumNode {
	public Topic(long id, SyllabusVersion syllabusVersion, Unit parent, String code, String name, int displayOrder) {

		super(id, syllabusVersion, parent, code, name, CurriculumLevel.TOPIC, displayOrder);
	}

	@Override
	public Unit getParent() {
		return (Unit) super.getParent();
	}
}
