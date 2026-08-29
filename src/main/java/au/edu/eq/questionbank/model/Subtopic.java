package au.edu.eq.questionbank.model;

public final class Subtopic extends CurriculumNode {
	public Subtopic(long id, SyllabusVersion syllabusVersion, Topic parent, String code, String name,
			int displayOrder) {

		super(id, syllabusVersion, parent, code, name, CurriculumLevel.SUBTOPIC, displayOrder);
	}

	@Override
	public Topic getParent() {
		return (Topic) super.getParent();
	}
}
