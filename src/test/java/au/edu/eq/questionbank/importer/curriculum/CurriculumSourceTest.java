package au.edu.eq.questionbank.importer.curriculum;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

class CurriculumSourceTest {

	private SyllabusVersion syllabusVersion;

	@Test
	void rejectsAnEmptyWorkbookList() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumSource(syllabusVersion, List.of()));
	}

	@Test
	void rejectsANullSyllabusVersion() {
		assertThrows(NullPointerException.class, () -> new CurriculumSource(null, List.of(Path.of("curriculum.xlsx"))));
	}

	@Test
	void rejectsANullWorkbookElement() {
		List<Path> workbooks = new ArrayList<>();
		workbooks.add(null);
		assertThrows(NullPointerException.class, () -> new CurriculumSource(syllabusVersion, workbooks));
	}

	@Test
	void rejectsANullWorkbookList() {
		assertThrows(IllegalArgumentException.class, () -> new CurriculumSource(syllabusVersion, null));
	}

	@BeforeEach
	void setUp() {
		Subject subject = new Subject(1, "Chemistry");
		syllabusVersion = new SyllabusVersion(1, subject, "2025", true);
	}

	@Test
	void takesAnImmutableSnapshotOfWorkbooks() {
		Path workbook = Path.of("unit-1-and-2.xlsx");
		List<Path> suppliedWorkbooks = new ArrayList<>(List.of(workbook));
		CurriculumSource source = new CurriculumSource(syllabusVersion, suppliedWorkbooks);
		suppliedWorkbooks.add(Path.of("unit-3-and-4.xlsx"));
		assertAll(() -> assertSame(syllabusVersion, source.syllabusVersion()),
				() -> assertEquals(List.of(workbook), source.workbooks()),
				() -> assertThrows(UnsupportedOperationException.class,
						() -> source.workbooks().add(Path.of("another.xlsx"))));
	}
}
