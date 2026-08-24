package au.edu.eq.questionbank.importer;

/**
 * One normalized descriptor row read from a curriculum workbook.
 *
 * @param unitName          the non-blank unit label
 * @param topicName         the non-blank topic label
 * @param subtopicName      the non-blank subtopic label
 * @param classificationCode the non-blank hierarchical classification code
 * @param descriptor        the non-blank syllabus descriptor text
 * @throws IllegalArgumentException if any component is {@code null} or blank
 */
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
