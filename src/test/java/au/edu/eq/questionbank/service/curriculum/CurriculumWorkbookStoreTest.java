package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurriculumWorkbookStoreTest {

	@TempDir
	Path tempDirectory;

	@Test
	void copiesExternalWorkbookIntoManagedCurriculumRoot() throws Exception {
		Path curriculumRoot = Files.createDirectory(tempDirectory.resolve("curriculum"));
		Path externalDirectory = Files.createDirectory(tempDirectory.resolve("external"));
		Path source = Files.writeString(externalDirectory.resolve("chemistry.xlsx"), "workbook-data");
		Path managed = new CurriculumWorkbookStore(curriculumRoot).manageWorkbook(source);
		assertEquals(curriculumRoot.resolve("chemistry.xlsx"), managed);
		assertEquals("workbook-data", Files.readString(managed));
		assertEquals("workbook-data", Files.readString(source));
	}

	@Test
	void reusesIdenticalManagedWorkbook() throws Exception {
		Path curriculumRoot = Files.createDirectory(tempDirectory.resolve("curriculum"));
		Path externalDirectory = Files.createDirectory(tempDirectory.resolve("external"));
		Path managed = Files.writeString(curriculumRoot.resolve("chemistry.xlsx"), "same-data");
		Path source = Files.writeString(externalDirectory.resolve("chemistry.xlsx"), "same-data");
		Path result = new CurriculumWorkbookStore(curriculumRoot).manageWorkbook(source);
		assertEquals(managed, result);
		assertEquals(1, Files.list(curriculumRoot).count());
	}

	@Test
	void sameFilenameWithDifferentBytesDoesNotOverwriteManagedWorkbook() throws Exception {
		Path curriculumRoot = Files.createDirectory(tempDirectory.resolve("curriculum"));
		Path externalDirectory = Files.createDirectory(tempDirectory.resolve("external"));
		Path existing = Files.writeString(curriculumRoot.resolve("chemistry.xlsx"), "existing-data");
		Path source = Files.writeString(externalDirectory.resolve("chemistry.xlsx"), "new-data");
		Path result = new CurriculumWorkbookStore(curriculumRoot).manageWorkbook(source);
		assertNotEquals(existing, result);
		assertTrue(result.startsWith(curriculumRoot));
		assertEquals("existing-data", Files.readString(existing));
		assertEquals("new-data", Files.readString(result));
	}

	@Test
	void workbookAlreadyInsideManagedRootIsUsedInPlace() throws Exception {
		Path curriculumRoot = Files.createDirectory(tempDirectory.resolve("curriculum"));
		Path source = Files.writeString(curriculumRoot.resolve("chemistry.xlsx"), "managed-data");
		Path result = new CurriculumWorkbookStore(curriculumRoot).manageWorkbook(source);
		assertEquals(source, result);
	}
}
