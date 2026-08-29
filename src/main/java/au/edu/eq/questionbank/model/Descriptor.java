package au.edu.eq.questionbank.model;

public final class Descriptor extends CurriculumNode {
	public Descriptor(long id, SyllabusVersion syllabusVersion, Subtopic parent, String code, String text,
			int displayOrder) {

		super(id, syllabusVersion, parent, code, text, CurriculumLevel.DESCRIPTOR, displayOrder);
	}

	public Descriptor(long id, SyllabusVersion syllabusVersion, Topic parent, String code, String text,
			int displayOrder) {

		super(id, syllabusVersion, parent, code, text, CurriculumLevel.DESCRIPTOR, displayOrder);
	}
}
