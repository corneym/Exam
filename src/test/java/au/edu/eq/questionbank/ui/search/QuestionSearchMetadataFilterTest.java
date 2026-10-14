package au.edu.eq.questionbank.ui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Descriptor;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;

class QuestionSearchMetadataFilterTest {

	// Curriculum and assessment fixtures.
	private Subject chemistry;
	private SyllabusVersion current;
	private Question currentWritten;
	private Question futureUnknown;
	private SyllabusVersion historical;
	private Question historicalMcq;
	private Question neapCurrentMcq;
	private Question physicsWritten;
	private ExamProvider qcaa;
	private Exam qcaa2024;
	private ExamBooklet qcaa2024Booklet;
	private ExamBooklet qcaa2024SecondBooklet;

	private static Question question(long id, ExamBooklet booklet, CurriculumNode classification,
			QuestionResponseType responseType, int marks) {
		return new Question(id, booklet, "Q" + id, "", marks, List.of(), classification, false, null, null,
				responseType);
	}

	@Test
	void allIncludesUnknownButNeverAnotherSubject() {
		QuestionSearchMetadataFilter filter = QuestionSearchMetadataFilter.unrestricted(chemistry);
		List<Question> results = filter
				.filter(List.of(physicsWritten, futureUnknown, currentWritten, historicalMcq, neapCurrentMcq));
		assertEquals(List.of(futureUnknown, currentWritten, historicalMcq, neapCurrentMcq), results);
	}

	@Test
	void examAndBookletRestrictionsCombine() {
		QuestionSearchMetadataFilter examFilter = new QuestionSearchMetadataFilter(chemistry, null, null, null,
				qcaa2024, null, null, null);
		assertTrue(examFilter.matches(historicalMcq));
		assertTrue(examFilter.matches(currentWritten));
		assertFalse(examFilter.matches(futureUnknown));
		QuestionSearchMetadataFilter bookletFilter = new QuestionSearchMetadataFilter(chemistry, null, null, null,
				qcaa2024, qcaa2024Booklet, null, null);
		assertTrue(bookletFilter.matches(historicalMcq));
		assertFalse(bookletFilter.matches(currentWritten));

		// A reconstructed Booklet with the same persistent ID
		// must select the same Questions.
		ExamBooklet reconstructed = new ExamBooklet(qcaa2024SecondBooklet.getId(), qcaa2024, "Paper 2",
				new SourceDocument(55, "reloaded.pdf"));
		QuestionSearchMetadataFilter reloadedFilter = new QuestionSearchMetadataFilter(chemistry, null, null, null,
				qcaa2024, reconstructed, null, null);
		assertTrue(reloadedFilter.matches(currentWritten));
		assertFalse(reloadedFilter.matches(historicalMcq));
	}

	@Test
	void filtersByProviderUsingPersistentIdentity() {
		QuestionSearchMetadataFilter filter = new QuestionSearchMetadataFilter(chemistry, new ExamProvider(10, "QCAA"),
				null, null, null, null, null, null);
		assertTrue(filter.matches(historicalMcq));
		assertTrue(filter.matches(currentWritten));
		assertTrue(filter.matches(futureUnknown));
		assertFalse(filter.matches(neapCurrentMcq));
		assertFalse(filter.matches(physicsWritten));
	}

	@Test
	void filtersYearRangeInclusively() {
		QuestionSearchMetadataFilter filter = new QuestionSearchMetadataFilter(chemistry, null, 2024, 2024, null, null,
				null, null);
		assertTrue(filter.matches(historicalMcq));
		assertTrue(filter.matches(currentWritten));
		assertTrue(filter.matches(neapCurrentMcq));
		assertFalse(filter.matches(futureUnknown));
		QuestionSearchMetadataFilter from2025 = new QuestionSearchMetadataFilter(chemistry, null, 2025, null, null,
				null, null, null);
		assertTrue(from2025.matches(futureUnknown));
		assertFalse(from2025.matches(historicalMcq));
		QuestionSearchMetadataFilter through2024 = new QuestionSearchMetadataFilter(chemistry, null, null, 2024, null,
				null, null, null);
		assertTrue(through2024.matches(historicalMcq));
		assertFalse(through2024.matches(futureUnknown));
	}

	@Test
	void invalidYearRangesAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new QuestionSearchMetadataFilter(chemistry, null, 2026, 2024, null, null, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> new QuestionSearchMetadataFilter(chemistry, null, 0, null, null, null, null, null));
	}

	@Test
	void multipleFiltersUseAndSemantics() {
		QuestionSearchMetadataFilter filter = new QuestionSearchMetadataFilter(chemistry, qcaa, 2024, 2024, qcaa2024,
				qcaa2024Booklet, QuestionResponseType.MULTIPLE_CHOICE, historical);
		assertTrue(filter.matches(historicalMcq));
		assertFalse(filter.matches(currentWritten));
		assertFalse(filter.matches(futureUnknown));
		assertFalse(filter.matches(neapCurrentMcq));
		assertFalse(filter.matches(physicsWritten));
	}

	@Test
	void originalSyllabusIsIndependentOfExamYear() {
		QuestionSearchMetadataFilter filter = new QuestionSearchMetadataFilter(chemistry, null, null, null, null, null,
				null, historical);
		assertTrue(filter.matches(historicalMcq));
		assertFalse(filter.matches(currentWritten));
		assertFalse(filter.matches(futureUnknown));

		// Syllabus selection is by persisted version identity.
		SyllabusVersion reloaded = new SyllabusVersion(historical.getId(), chemistry, "2019", false);
		QuestionSearchMetadataFilter reloadedFilter = new QuestionSearchMetadataFilter(chemistry, null, null, null,
				null, null, null, reloaded);
		assertTrue(reloadedFilter.matches(historicalMcq));
	}

	@Test
	void responseTypeOffersOnlyMultipleChoiceAndWritten() {
		QuestionSearchMetadataFilter multipleChoice = new QuestionSearchMetadataFilter(chemistry, null, null, null,
				null, null, QuestionResponseType.MULTIPLE_CHOICE, null);
		QuestionSearchMetadataFilter written = new QuestionSearchMetadataFilter(chemistry, null, null, null, null, null,
				QuestionResponseType.WRITTEN_RESPONSE, null);
		assertTrue(multipleChoice.matches(historicalMcq));
		assertFalse(multipleChoice.matches(currentWritten));
		assertFalse(multipleChoice.matches(futureUnknown));
		assertTrue(written.matches(currentWritten));
		assertFalse(written.matches(historicalMcq));
		assertThrows(IllegalArgumentException.class, () -> new QuestionSearchMetadataFilter(chemistry, null, null, null,
				null, null, QuestionResponseType.UNKNOWN, null));
	}

	@BeforeEach
	void setUp() {
		chemistry = new Subject(1, "Chemistry");
		Subject physics = new Subject(2, "Physics");
		qcaa = new ExamProvider(10, "QCAA");
		ExamProvider neap = new ExamProvider(11, "NEAP");
		historical = new SyllabusVersion(20, chemistry, "2019", false);
		current = new SyllabusVersion(21, chemistry, "2025", true);
		SyllabusVersion physicsSyllabus = new SyllabusVersion(22, physics, "2025", true);
		Unit oldUnit = new Unit(30, historical, "1", "Historical Unit", 1);
		Topic oldTopic = new Topic(31, historical, oldUnit, "1.1", "Historical Topic", 1);
		Descriptor oldDescriptor = new Descriptor(32, historical, oldTopic, "1.1.1", "Historical Descriptor", 1);
		Unit currentUnit = new Unit(33, current, "3", "Current Unit", 1);
		Topic currentTopic = new Topic(34, current, currentUnit, "3.1", "Current Topic", 1);
		Descriptor currentDescriptor = new Descriptor(35, current, currentTopic, "3.1.1", "Current Descriptor", 1);
		Unit physicsUnit = new Unit(36, physicsSyllabus, "3", "Physics Unit", 1);
		Topic physicsTopic = new Topic(37, physicsSyllabus, physicsUnit, "3.1", "Physics Topic", 1);
		Descriptor physicsDescriptor = new Descriptor(38, physicsSyllabus, physicsTopic, "3.1.1", "Physics Descriptor",
				1);
		qcaa2024 = new Exam(40, chemistry, qcaa, 2024, "External Exam");
		Exam qcaa2026 = new Exam(41, chemistry, qcaa, 2026, "External Exam");
		Exam neap2024 = new Exam(42, chemistry, neap, 2024, "Practice Exam");
		Exam physics2024 = new Exam(43, physics, qcaa, 2024, "Physics Exam");
		qcaa2024Booklet = new ExamBooklet(60, qcaa2024, "Paper 1", new SourceDocument(50, "paper-1.pdf"));
		qcaa2024SecondBooklet = new ExamBooklet(61, qcaa2024, "Paper 2", new SourceDocument(51, "paper-2.pdf"));
		ExamBooklet qcaa2026Booklet = new ExamBooklet(62, qcaa2026, "Paper 1", new SourceDocument(52, "paper-3.pdf"));
		ExamBooklet neap2024Booklet = new ExamBooklet(63, neap2024, "Practice", new SourceDocument(53, "practice.pdf"));
		ExamBooklet physicsBooklet = new ExamBooklet(64, physics2024, "Physics", new SourceDocument(54, "physics.pdf"));
		historicalMcq = question(70, qcaa2024Booklet, oldDescriptor, QuestionResponseType.MULTIPLE_CHOICE, 1);
		currentWritten = question(71, qcaa2024SecondBooklet, currentDescriptor, QuestionResponseType.WRITTEN_RESPONSE,
				3);
		futureUnknown = question(72, qcaa2026Booklet, currentDescriptor, QuestionResponseType.UNKNOWN, 2);
		neapCurrentMcq = question(73, neap2024Booklet, currentDescriptor, QuestionResponseType.MULTIPLE_CHOICE, 1);
		physicsWritten = question(74, physicsBooklet, physicsDescriptor, QuestionResponseType.WRITTEN_RESPONSE, 2);
	}
}
