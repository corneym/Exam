package au.edu.eq.questionbank.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CurriculumMappingImportRowTest {

	@Test
	void rejectsBlankSourceCode() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingImportRow("", "3.2.1"));
	}

	@Test
	void rejectsBlankTargetCode() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumMappingImportRow("3.1.2", ""));
	}

	@Test
	void storesSourceAndTargetCodes() {
		CurriculumMappingImportRow row = new CurriculumMappingImportRow("3.1.2", "3.2.1");

		assertEquals("3.1.2", row.sourceCode());
		assertEquals("3.2.1", row.targetCode());
	}
}