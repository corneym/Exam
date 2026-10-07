package au.edu.eq.questionbank.service.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;

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
	void copiesExternalWorkbookIntoSubjectFirstVersionDirectory() throws Exception {
		Path dataRoot = tempDirectory.resolve("subject-first-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path externalDirectory = Files.createDirectories(tempDirectory.resolve("subject-first-external"));
		Path source = Files.writeString(externalDirectory.resolve("chemistry.xlsx"), "workbook-data");
		Path managed = new CurriculumWorkbookStore(layout).manageWorkbook("Chemistry", "2025", source);
		Path expected = layout.curriculumWorkbookDirectory("Chemistry", "2025").resolve("chemistry.xlsx");
		assertEquals(expected, managed);
		assertEquals("workbook-data", Files.readString(managed));

		// Selecting a source from outside managed storage must not alter or remove it.
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
	void reusesIdenticalSubjectFirstWorkbook() throws Exception {
		Path dataRoot = tempDirectory.resolve("reuse-subject-first-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		Path workbookDirectory = layout.curriculumWorkbookDirectory("Chemistry", "2025");
		Files.createDirectories(workbookDirectory);
		Path managed = Files.writeString(workbookDirectory.resolve("chemistry.xlsx"), "same-data");
		Path externalDirectory = Files.createDirectories(tempDirectory.resolve("reuse-subject-first-external"));
		Path source = Files.writeString(externalDirectory.resolve("chemistry.xlsx"), "same-data");
		Path result = new CurriculumWorkbookStore(layout).manageWorkbook("Chemistry", "2025", source);
		assertEquals(managed, result);
		try (var files = Files.list(workbookDirectory)) {
			assertEquals(1, files.count());
		}
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
	void subjectFirstWorkbookStorageIsolatesVersionsAndSameNameCollisions() throws Exception {
		Path dataRoot = tempDirectory.resolve("isolated-subject-first-data");
		ManagedDataLayout layout = new ManagedDataLayout(dataRoot);
		CurriculumWorkbookStore store = new CurriculumWorkbookStore(layout);
		Path firstExternalDirectory = Files.createDirectories(tempDirectory.resolve("first-external"));
		Path firstSource = Files.writeString(firstExternalDirectory.resolve("curriculum.xlsx"), "first-data");
		Path firstManaged = store.manageWorkbook("Chemistry", "2025", firstSource);
		Path secondExternalDirectory = Files.createDirectories(tempDirectory.resolve("second-external"));
		Path secondSource = Files.writeString(secondExternalDirectory.resolve("curriculum.xlsx"), "different-data");
		Path collisionManaged = store.manageWorkbook("Chemistry", "2025", secondSource);
		assertNotEquals(firstManaged, collisionManaged);
		assertTrue(collisionManaged.startsWith(layout.curriculumWorkbookDirectory("Chemistry", "2025")));
		assertEquals("first-data", Files.readString(firstManaged));
		assertEquals("different-data", Files.readString(collisionManaged));

		// The same filename and bytes used for another version or Subject belong to a
		// different authoritative directory rather than being reused cross-version.
		Path olderVersion = store.manageWorkbook("Chemistry", "2019", firstSource);
		Path otherSubject = store.manageWorkbook("Physics", "2025", firstSource);
		assertEquals(layout.curriculumWorkbookDirectory("Chemistry", "2019").resolve("curriculum.xlsx"), olderVersion);
		assertEquals(layout.curriculumWorkbookDirectory("Physics", "2025").resolve("curriculum.xlsx"), otherSubject);
		assertNotEquals(firstManaged, olderVersion);
		assertNotEquals(firstManaged, otherSubject);
	}

	@Test
	void workbookAlreadyInsideManagedRootIsUsedInPlace() throws Exception {
		Path curriculumRoot = Files.createDirectory(tempDirectory.resolve("curriculum"));
		Path source = Files.writeString(curriculumRoot.resolve("chemistry.xlsx"), "managed-data");
		Path result = new CurriculumWorkbookStore(curriculumRoot).manageWorkbook(source);
		assertEquals(source, result);
	}
}
