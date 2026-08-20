package au.edu.eq.questionbank.importer;

public record CurriculumImportRow(String unitName, String topicName, String subtopicName, String classificationCode,
		String descriptor) {

	public CurriculumImportRow {
		if (unitName == null || unitName.isBlank()) {
			throw new IllegalArgumentException("unitName must not be blank");
		}
		if (topicName == null || topicName.isBlank()) {
			throw new IllegalArgumentException("topicName must not be blank");
		}
		if (subtopicName == null || subtopicName.isBlank()) {
			throw new IllegalArgumentException("subtopicName must not be blank");
		}
		if (classificationCode == null || classificationCode.isBlank()) {
			throw new IllegalArgumentException("classificationCode must not be blank");
		}
		if (descriptor == null || descriptor.isBlank()) {
			throw new IllegalArgumentException("descriptor must not be blank");
		}
	}
}
