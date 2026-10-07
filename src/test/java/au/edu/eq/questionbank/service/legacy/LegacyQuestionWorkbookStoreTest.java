package au.edu.eq.questionbank.service.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;

class LegacyQuestionWorkbookStoreTest {

	@TempDir
	Path tempDirectory;

	@Test
	void copiesExternalWorkbookIntoOwningSubjectVersion() throws Exception {
		Path dataRoot = tempDirectory.resolve("data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		LegacyQuestionWorkbookStore store = new LegacyQuestionWorkbookStore(layout);
		Path externalDirectory = Files.createDirectories(tempDirectory.resolve("incoming"));
		Path source = Files.writeString(externalDirectory.resolve("legacy-questions.xlsx"), "workbook-data");
		Path managed = store.manageWorkbook("Chemistry", "2019", source);
		assertEquals(layout.legacyImportDirectory("Chemistry", "2019").resolve("legacy-questions.xlsx"), managed);
		assertEquals("workbook-data", Files.readString(managed));
		assertEquals("workbook-data", Files.readString(source));
	}

	@Test
	void failedSourceReadCreatesNoManagedWorkbook() {
		Path dataRoot = tempDirectory.resolve("failure-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		LegacyQuestionWorkbookStore store = new LegacyQuestionWorkbookStore(layout);
		Path missing = tempDirectory.resolve("missing.xlsx");
		assertThrows(IOException.class, () -> store.manageWorkbook("Chemistry", "2019", missing));
		assertFalse(Files.exists(layout.legacyImportDirectory("Chemistry", "2019")));
	}

	@Test
	void isolatesWorkbooksBySubjectAndSyllabusVersion() throws Exception {
		Path dataRoot = tempDirectory.resolve("isolation-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		LegacyQuestionWorkbookStore store = new LegacyQuestionWorkbookStore(layout);
		Path source = Files.writeString(tempDirectory.resolve("shared.xlsx"), "same-data");
		Path chemistry2019 = store.manageWorkbook("Chemistry", "2019", source);
		Path chemistry2025 = store.manageWorkbook("Chemistry", "2025", source);
		Path physics2019 = store.manageWorkbook("Physics", "2019", source);
		assertEquals(layout.legacyImportDirectory("Chemistry", "2019").resolve("shared.xlsx"), chemistry2019);
		assertEquals(layout.legacyImportDirectory("Chemistry", "2025").resolve("shared.xlsx"), chemistry2025);
		assertEquals(layout.legacyImportDirectory("Physics", "2019").resolve("shared.xlsx"), physics2019);
		assertNotEquals(chemistry2019, chemistry2025);
		assertNotEquals(chemistry2019, physics2019);
	}

	@Test
	void reusesIdenticalWorkbookAndPreservesDifferentSameNameWorkbook() throws Exception {
		Path dataRoot = tempDirectory.resolve("collision-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		LegacyQuestionWorkbookStore store = new LegacyQuestionWorkbookStore(layout);
		Path firstDirectory = Files.createDirectories(tempDirectory.resolve("first"));
		Path first = Files.writeString(firstDirectory.resolve("legacy.xlsx"), "first-data");
		Path firstManaged = store.manageWorkbook("Chemistry", "2019", first);
		Path identicalDirectory = Files.createDirectories(tempDirectory.resolve("identical"));
		Path identical = Files.writeString(identicalDirectory.resolve("legacy.xlsx"), "first-data");
		Path reused = store.manageWorkbook("Chemistry", "2019", identical);
		assertEquals(firstManaged, reused);
		Path differentDirectory = Files.createDirectories(tempDirectory.resolve("different"));
		Path different = Files.writeString(differentDirectory.resolve("legacy.xlsx"), "different-data");
		Path secondManaged = store.manageWorkbook("Chemistry", "2019", different);
		assertNotEquals(firstManaged, secondManaged);
		assertTrue(secondManaged.getFileName().toString().endsWith("--legacy.xlsx"));
		assertEquals("first-data", Files.readString(firstManaged));
		assertEquals("different-data", Files.readString(secondManaged));
	}
}
