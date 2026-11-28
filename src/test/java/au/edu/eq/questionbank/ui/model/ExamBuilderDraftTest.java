package au.edu.eq.questionbank.ui.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.model.Answer;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.AnswerRegion;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.QuestionRegion;
import au.edu.eq.questionbank.model.QuestionResponseType;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.SourceQuestion;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.pdf.PrintableAssessmentPlan;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.pdf.PrintableQuestionNumbering;

class ExamBuilderDraftTest {

	private final Subject chemistry = new Subject(1, "Chemistry");
	@TempDir
	Path tempDirectory;

	@Test
	void draftFilePreservesExaminationUuidAndSupportsLegacyFiles() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(question));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2026, 5, 60, 5, 75);
		Path file = tempDirectory.resolve("identity.exam.json");
		service.save(file, draft, settings);
		var loaded = service.load(file, chemistry);
		assertEquals(draft.getExaminationId(), loaded.draft().getExaminationId());
		assertFalse(loaded.draft().isFinalised());
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode legacy = (ObjectNode) mapper.readTree(file.toFile());
		legacy.put("version", 1);
		legacy.remove("examinationId");
		legacy.remove("finalised");
		mapper.writeValue(file.toFile(), legacy);
		var oldFile = service.load(file, chemistry);
		assertNotEquals(draft.getExaminationId(), oldFile.draft().getExaminationId());
		assertFalse(oldFile.draft().isFinalised());
		assertEquals(List.of(question), oldFile.draft().getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
		service.save(file, oldFile.draft(), settings);
		assertEquals(oldFile.draft().getExaminationId(), service.load(file, chemistry).draft().getExaminationId());
	}

	@Test
	void draftFilePreservesPaperTwoInclusionAndReadsEarlierVersions() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(question));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2026, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("optional-paper-two.exam.json");
		service.save(file, draft, settings, false);
		var loaded = service.load(file, chemistry);
		assertFalse(loaded.includePaperTwo());
		assertEquals(draft.getExaminationId(), loaded.draft().getExaminationId());
		assertEquals(settings, loaded.settings());
		assertEquals(List.of(question), loaded.draft().getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode current = (ObjectNode) mapper.readTree(file.toFile());
		assertEquals(3, current.path("version").intValue());
		assertFalse(current.path("includePaperTwo").booleanValue());

		// Existing format-two files did not have an inclusion flag.
		ObjectNode versionTwo = current.deepCopy();
		versionTwo.put("version", 2);
		versionTwo.remove("includePaperTwo");
		mapper.writeValue(file.toFile(), versionTwo);
		var legacyTwo = service.load(file, chemistry);
		assertTrue(legacyTwo.includePaperTwo());
		assertEquals(draft.getExaminationId(), legacyTwo.draft().getExaminationId());

		// Existing format-one files additionally lacked
		// persistent examination identity and lifecycle metadata.
		ObjectNode versionOne = versionTwo.deepCopy();
		versionOne.put("version", 1);
		versionOne.remove("examinationId");
		versionOne.remove("finalised");
		mapper.writeValue(file.toFile(), versionOne);
		var legacyOne = service.load(file, chemistry);
		assertTrue(legacyOne.includePaperTwo());
		assertNotEquals(draft.getExaminationId(), legacyOne.draft().getExaminationId());

		// Re-saving a legacy examination uses the current format.
		service.save(file, legacyOne.draft(), legacyOne.settings(), false);
		var converted = service.load(file, chemistry);
		assertFalse(converted.includePaperTwo());
		assertEquals(legacyOne.draft().getExaminationId(), converted.draft().getExaminationId());
	}

	@Test
	void draftFileRejectsInvalidPaperTwoInclusion() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question written = createQuestion(10, booklet, "21", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, written));
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(written));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2026, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("invalid-paper-two.exam.json");
		service.save(file, draft, settings, true);
		assertTrue(service.load(file, chemistry).includePaperTwo());
		String original = Files.readString(file);

		// An inconsistent save request must not replace a valid file.
		assertThrows(IllegalArgumentException.class, () -> service.save(file, draft, settings, false));
		assertEquals(original, Files.readString(file));
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode inconsistent = (ObjectNode) mapper.readTree(original);
		inconsistent.put("includePaperTwo", false);
		mapper.writeValue(file.toFile(), inconsistent);
		IOException failure = assertThrows(IOException.class, () -> service.load(file, chemistry));
		assertTrue(failure.getMessage().contains("Paper 2 is excluded but contains Questions"));

		// Current-format files require a genuine boolean flag.
		ObjectNode missing = (ObjectNode) mapper.readTree(original);
		missing.remove("includePaperTwo");
		mapper.writeValue(file.toFile(), missing);
		assertThrows(IOException.class, () -> service.load(file, chemistry));
		ObjectNode malformed = (ObjectNode) mapper.readTree(original);
		malformed.put("includePaperTwo", "false");
		mapper.writeValue(file.toFile(), malformed);
		assertThrows(IOException.class, () -> service.load(file, chemistry));
	}

	@Test
	void draftFileRejectsWrongSubjectAndMissingOrIncompleteQuestions() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("validation.exam.json");
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(
				id -> id == chemistry.getId() ? subjectUuid : UUID.randomUUID(), _ -> questionUuid,
				_ -> Optional.of(question));
		service.save(file, draft, settings);
		IOException wrongSubject = assertThrows(IOException.class, () -> service.load(file, new Subject(2, "Physics")));
		assertTrue(wrongSubject.getMessage().contains("another Subject"));
		ExamBuilderDraftFile missingQuestionService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.empty());
		IOException missingQuestion = assertThrows(IOException.class,
				() -> missingQuestionService.load(file, chemistry));
		assertTrue(missingQuestion.getMessage().contains("no longer exists"));
		Question incomplete = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, false);
		ExamBuilderDraftFile incompleteQuestionService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(incomplete));
		IOException incompleteAnswer = assertThrows(IOException.class,
				() -> incompleteQuestionService.load(file, chemistry));
		assertTrue(incompleteAnswer.getMessage().contains("no longer eligible"));

		// Rejected load operations must not alter the
		// examination already being edited.
		assertEquals(List.of(question), draft.getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
	}

	@Test
	void draftFilesPreserveOrderSettingsAndPortableIdentities() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question mcq = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(20, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(30, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		List<Question> corpus = List.of(partB, mcq, partA);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, mcq));
		assertTrue(draft.addWholeQuestion(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, partB, corpus));
		UUID subjectUuid = UUID.randomUUID();
		UUID mcqUuid = UUID.randomUUID();
		UUID partAUuid = UUID.randomUUID();
		UUID partBUuid = UUID.randomUUID();
		Map<Long, UUID> portableIds = Map.of(10L, mcqUuid, 20L, partAUuid, 30L, partBUuid);
		ExamBuilderDraftFile writer = new ExamBuilderDraftFile(_ -> subjectUuid, portableIds::get,
				_ -> Optional.empty());
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("chemistry.exam.json");
		writer.save(file, draft, settings);
		assertTrue(Files.isRegularFile(file));
		String json = Files.readString(file);
		assertTrue(json.contains(mcqUuid.toString()));
		assertTrue(json.contains(partAUuid.toString()));
		assertTrue(json.contains(partBUuid.toString()));

		// Simulate a restored corpus retaining portable identities
		// but assigning different local database identifiers.
		Subject restoredSubject = new Subject(200, "Chemistry");
		ExamBooklet restoredBooklet = createBooklet(restoredSubject, 200);
		SourceQuestion restoredSource = new SourceQuestion(201, restoredBooklet, "21");
		Question restoredMcq = createQuestion(1010, restoredBooklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null,
				true);
		Question restoredPartA = createQuestion(1020, restoredBooklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE,
				restoredSource, true);
		Question restoredPartB = createQuestion(1030, restoredBooklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE,
				restoredSource, true);
		Map<UUID, Question> restoredQuestions = Map.of(mcqUuid, restoredMcq, partAUuid, restoredPartA, partBUuid,
				restoredPartB);
		ExamBuilderDraftFile reader = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> {
			throw new AssertionError("Not saving a draft.");
		}, id -> Optional.ofNullable(restoredQuestions.get(id)),
				() -> List.of(restoredPartB, restoredMcq, restoredPartA));
		ExamBuilderDraftFile.LoadedDraft loaded = reader.load(file, restoredSubject);
		assertEquals(settings, loaded.settings());
		assertEquals(restoredSubject, loaded.draft().getSubject());
		assertEquals(List.of(restoredMcq), loaded.draft().getQuestions(ExamBuilderDraft.Section.MULTIPLE_CHOICE));
		assertEquals(List.of(restoredPartA, restoredPartB),
				loaded.draft().getQuestions(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN));
		assertEquals(2, loaded.draft().getTopLevelSelectedCount());
		assertEquals(3, loaded.draft().getTotalSelectedCount());
		PrintableAssessmentPlan plan = loaded.draft().buildPlan();
		assertEquals(List.of("2(a)", "2(b)"),
				plan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(6, plan.getTotalMarks());
		assertEquals(10, mcq.getId());
		assertEquals(1010, restoredMcq.getId());
	}

	@Test
	void failedDraftSavePreservesExistingFile() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, question));
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		UUID subjectUuid = UUID.randomUUID();
		UUID questionUuid = UUID.randomUUID();
		Path file = tempDirectory.resolve("existing.exam.json");
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> questionUuid,
				_ -> Optional.of(question));
		service.save(file, draft, settings);
		byte[] original = Files.readAllBytes(file);
		ExamBuilderDraftFile failingService = new ExamBuilderDraftFile(_ -> subjectUuid, _ -> {
			throw new IllegalStateException("Missing Question identity");
		}, _ -> Optional.empty());
		assertThrows(IOException.class, () -> failingService.save(file, draft, settings));
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	void movesMultipartQuestionsWithoutSplittingTheirDestinationGroup() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion firstSource = new SourceQuestion(1, booklet, "21");
		SourceQuestion secondSource = new SourceQuestion(2, booklet, "22");
		Question firstA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, firstSource,
				true);
		Question firstB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, firstSource,
				true);
		Question secondA = createQuestion(30, booklet, "22a", 4, QuestionResponseType.WRITTEN_RESPONSE, secondSource,
				true);
		Question secondB = createQuestion(40, booklet, "22b", 5, QuestionResponseType.WRITTEN_RESPONSE, secondSource,
				true);
		List<Question> corpus = List.of(firstA, firstB, secondA, secondB);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, firstA, corpus));
		assertTrue(draft.addWholeQuestion(section, secondA, corpus));
		assertEquals(-1, draft.adjacentWholeQuestionIndex(section, firstB.getId(), -1));
		assertEquals(2, draft.adjacentWholeQuestionIndex(section, firstA.getId(), 1));
		assertEquals(-1, draft.adjacentWholeQuestionIndex(section, secondA.getId(), 1));

		// Reordering within the same multipart Question
		// must be rejected.
		assertFalse(draft.move(section, firstA.getId(), 1));

		// Moving towards the first part of another group
		// must move past the entire destination group.
		assertTrue(draft.move(section, firstB.getId(), 2));
		assertEquals(List.of(secondA, secondB, firstA, firstB), draft.getQuestions(section));

		// Moving upwards towards the last constituent part
		// must insert before the entire destination group.
		assertTrue(draft.move(section, firstB.getId(), 1));
		assertEquals(List.of(firstA, firstB, secondA, secondB), draft.getQuestions(section));
		assertEquals(14, draft.getTotalMarks(section));
		assertEquals(2, draft.buildPlan().getPaperOneQuestionCount());
	}

	@Test
	void rejectsDuplicatesAcrossTheEntireExamination() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question original = createQuestion(1, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null, true);

		// Simulate a fresh retrieval of the same persistent
		// Question using a different Java object.
		Question retrievedAgain = createQuestion(1, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null,
				true);
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, original));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, retrievedAgain));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, retrievedAgain));
		assertEquals(1, draft.getTotalSelectedCount());
	}

	@Test
	void rejectsEntireMultipartQuestionWhenAnyPartIsIneligible() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, false);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		List<Question> corpus = List.of(partA, partB);

		// A complete selected part must not permit an
		// incomplete constituent part to enter the draft.
		assertTrue(draft.eligibleWholeQuestion(section, partA, corpus).isEmpty());
		assertFalse(draft.addWholeQuestion(section, partA, corpus));
		assertTrue(draft.isEmpty());
		assertEquals(0, draft.getTotalMarks(section));
	}

	@Test
	void rejectsIncompleteAnswersAndIncorrectResponseTypes() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question mcq = createQuestion(1, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		Question unanswered = createQuestion(2, booklet, "8", 1, QuestionResponseType.MULTIPLE_CHOICE, null, false);
		Question written = createQuestion(3, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		assertTrue(draft.isEmpty());
		assertFalse(draft.canAdd(ExamBuilderDraft.Section.MULTIPLE_CHOICE, unanswered));
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, unanswered));
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, written));
		assertFalse(draft.add(ExamBuilderDraft.Section.PAPER_ONE_WRITTEN, mcq));
		assertTrue(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, mcq));
		assertTrue(draft.add(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, written));
		assertEquals(2, draft.getTotalSelectedCount());
	}

	@Test
	void rejectsIndependentMultipartAdditionAndInvalidSelectionBatches() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question standalone = createQuestion(30, booklet, "25", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		Question incomplete = createQuestion(40, booklet, "26", 5, QuestionResponseType.WRITTEN_RESPONSE, null, false);
		List<Question> corpus = List.of(partB, incomplete, standalone, partA);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;

		// The original individual-record API must no longer
		// provide a route around complete multipart selection.
		assertFalse(draft.canAdd(section, partA));
		assertFalse(draft.add(section, partA));
		assertTrue(draft.isEmpty());

		// A later invalid Question must cause the entire
		// multi-Question selection operation to fail.
		assertFalse(draft.addSelections(section, List.of(partB, standalone, incomplete), corpus));
		assertTrue(draft.isEmpty());

		// Repeated identical identifiers are invalid.
		assertFalse(draft.addSelections(section, List.of(partB, partB), corpus));
		assertTrue(draft.isEmpty());

		// Distinct constituent identifiers represent one group.
		assertTrue(draft.addSelections(section, List.of(partB, partA, standalone), corpus));
		assertEquals(List.of(partA, partB, standalone), draft.getQuestions(section));
		assertEquals(2, draft.getTopLevelSelectedCount());
		assertEquals(3, draft.getTotalSelectedCount());
		assertEquals(9, draft.getTotalMarks(section));

		// An already selected group cannot be added again,
		// even by selecting a different constituent.
		assertFalse(draft.addSelections(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN, List.of(partA), corpus));
		assertTrue(draft.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN).isEmpty());
	}

	@Test
	void rejectsLegacyDraftsContainingPartialOrIncorrectlyOrderedQuestions() throws Exception {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		List<Question> corpus = List.of(partA, partB);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(section, partA, corpus));
		UUID subjectUuid = UUID.randomUUID();
		UUID partAUuid = UUID.randomUUID();
		UUID partBUuid = UUID.randomUUID();
		Map<Long, UUID> portableIds = Map.of(10L, partAUuid, 20L, partBUuid);
		Map<UUID, Question> questions = Map.of(partAUuid, partA, partBUuid, partB);
		ExamBuilderDraftFile service = new ExamBuilderDraftFile(_ -> subjectUuid, portableIds::get,
				id -> Optional.ofNullable(questions.get(id)), () -> corpus);
		PrintableExamSettings settings = new PrintableExamSettings("Chemistry", 2027, 5, 60, 10, 75);
		Path file = tempDirectory.resolve("legacy.exam.json");
		service.save(file, draft, settings);
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(file.toFile());
		ArrayNode identifiers = (ArrayNode) root.path("sections").path(section.name());

		// Simulate a legacy draft containing only part A.
		identifiers.remove(1);
		mapper.writeValue(file.toFile(), root);
		IOException incomplete = assertThrows(IOException.class, () -> service.load(file, chemistry));
		assertTrue(incomplete.getMessage().contains("incomplete or incorrectly ordered multipart Question"));

		// Simulate a legacy draft containing all parts,
		// but in an incorrect presentation order.
		identifiers.removeAll();
		identifiers.add(partBUuid.toString());
		identifiers.add(partAUuid.toString());
		mapper.writeValue(file.toFile(), root);
		IOException reordered = assertThrows(IOException.class, () -> service.load(file, chemistry));
		assertTrue(reordered.getMessage().contains("incomplete or incorrectly ordered multipart Question"));

		// Simulate an old draft allocating the same multipart
		// Question's constituent parts to different papers.
		identifiers.removeAll();
		identifiers.add(partAUuid.toString());
		ArrayNode paperTwo = (ArrayNode) root.path("sections").path(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN.name());
		paperTwo.add(partBUuid.toString());
		mapper.writeValue(file.toFile(), root);
		assertThrows(IOException.class, () -> service.load(file, chemistry));

		// Failed loading must not modify the active draft.
		assertEquals(List.of(partA, partB), draft.getQuestions(section));
		assertEquals(5, draft.getTotalMarks(section));
	}

	@Test
	void rejectsMultipartDuplicatesAcrossExaminationSections() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		List<Question> corpus = List.of(partA, partB);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.addWholeQuestion(paperOne, partA, corpus));
		assertFalse(draft.addWholeQuestion(paperOne, partB, corpus));
		assertFalse(draft.addWholeQuestion(paperTwo, partA, corpus));
		assertFalse(draft.addWholeQuestion(paperTwo, partB, corpus));
		assertEquals(List.of(partA, partB), draft.getQuestions(paperOne));
		assertTrue(draft.getQuestions(paperTwo).isEmpty());
		assertEquals(2, draft.getTotalSelectedCount());
		assertEquals(5, draft.buildPlan().getTotalMarks());
	}

	@Test
	void rejectsQuestionsFromAnotherSubject() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		Subject physics = new Subject(2, "Physics");
		Question physicsQuestion = createQuestion(1, createBooklet(physics, 2), "7", 1,
				QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertFalse(draft.add(ExamBuilderDraft.Section.MULTIPLE_CHOICE, physicsQuestion));
		assertTrue(draft.isEmpty());
	}

	@Test
	void rejectsSelectionMissingFromAuthoritativeCorpus() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertFalse(draft.addWholeQuestion(section, partA, List.of(partB)));
		assertTrue(draft.isEmpty());
	}

	@Test
	void reordersQuestionsAndRecalculatesGeneratedNumbering() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question mcq = createQuestion(1, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		Question partA = createQuestion(2, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(3, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question standalone = createQuestion(4, booklet, "25", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		List<Question> corpus = List.of(mcq, partA, partB, standalone);
		var mcqSection = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		var writtenSection = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(draft.addWholeQuestion(mcqSection, mcq, corpus));
		assertTrue(draft.addWholeQuestion(writtenSection, partB, corpus));
		assertTrue(draft.addWholeQuestion(writtenSection, standalone, corpus));
		PrintableAssessmentPlan originalPlan = draft.buildPlan();
		assertEquals(List.of("2(a)", "2(b)", "3"),
				originalPlan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(9, draft.getTotalMarks(writtenSection));
		assertTrue(draft.move(writtenSection, standalone.getId(), 0));
		PrintableAssessmentPlan reordered = draft.buildPlan();
		assertEquals(List.of("2", "3(a)", "3(b)"),
				reordered.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());

		// Removing either constituent must remove the
		// entire multipart Question.
		assertTrue(draft.remove(writtenSection, partB.getId()));
		assertEquals(List.of(standalone), draft.getQuestions(writtenSection));
		PrintableAssessmentPlan reduced = draft.buildPlan();
		assertEquals(List.of("2"),
				reduced.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(4, draft.getTotalMarks(writtenSection));

		// Removal releases every constituent part for
		// selection elsewhere in this unconfirmed draft.
		assertFalse(draft.contains(partA.getId()));
		assertFalse(draft.contains(partB.getId()));
		assertEquals(List.of(partA, partB), draft.eligibleWholeQuestion(writtenSection, partA, corpus));

		// Previously constructed plans remain unchanged.
		assertEquals(List.of("2(a)", "2(b)", "3"),
				originalPlan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals("21b", partB.getQuestionCode());
	}

	@Test
	void replacesDraftSelectionsWithoutSharingMutableLists() {
		ExamBuilderDraft sourceDraft = new ExamBuilderDraft(chemistry);
		ExamBuilderDraft destinationDraft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		assertTrue(sourceDraft.addWholeQuestion(section, partA, List.of(partA, partB)));
		destinationDraft.replaceWith(sourceDraft);
		assertEquals(List.of(partA, partB), destinationDraft.getQuestions(section));
		assertEquals(1, destinationDraft.getTopLevelSelectedCount());
		sourceDraft.clear();
		assertTrue(sourceDraft.isEmpty());
		assertEquals(List.of(partA, partB), destinationDraft.getQuestions(section));

		// Self-replacement must not clear the draft.
		destinationDraft.replaceWith(destinationDraft);
		assertEquals(2, destinationDraft.getTotalSelectedCount());
		ExamBuilderDraft wrongSubject = new ExamBuilderDraft(new Subject(2, "Physics"));
		assertThrows(IllegalArgumentException.class, () -> destinationDraft.replaceWith(wrongSubject));
		assertEquals(2, destinationDraft.getTotalSelectedCount());
	}

	@Test
	void resolvesMultipartMembershipByPersistentSourceIdentity() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet firstBooklet = createBooklet(chemistry, 1);
		ExamBooklet secondBooklet = createBooklet(chemistry, 2);
		SourceQuestion firstSource = new SourceQuestion(1, firstBooklet, "21");

		// Simulate independent retrieval of the same
		// persistent SourceQuestion identity.
		SourceQuestion retrievedSource = new SourceQuestion(1, firstBooklet, "21");
		SourceQuestion unrelatedSource = new SourceQuestion(2, secondBooklet, "21");
		Question partA = createQuestion(10, firstBooklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, firstSource,
				true);
		Question partB = createQuestion(20, firstBooklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE,
				retrievedSource, true);
		Question unrelatedPart = createQuestion(30, secondBooklet, "21a", 4, QuestionResponseType.WRITTEN_RESPONSE,
				unrelatedSource, true);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		List<Question> corpus = List.of(unrelatedPart, partB, partA);
		assertTrue(draft.addWholeQuestion(section, partA, corpus));
		assertEquals(List.of(partA, partB), draft.getQuestions(section));

		// Identical printed Question codes in another
		// booklet must not cause an unrelated exclusion.
		assertEquals(List.of(unrelatedPart), draft.eligibleWholeQuestion(section, unrelatedPart, corpus));
		assertTrue(draft.addWholeQuestion(section, unrelatedPart, corpus));
	}

	@Test
	void selectingAnyPartAddsEntireMultipartQuestionInOrder() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(30, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(10, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partC = createQuestion(20, booklet, "21c", 4, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question unrelated = createQuestion(40, booklet, "22", 5, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;

		// Corpus order and persistent ID order deliberately
		// differ from the original constituent part order.
		List<Question> corpus = List.of(partC, unrelated, partB, partA);
		assertEquals(List.of(partA, partB, partC), draft.eligibleWholeQuestion(section, partB, corpus));

		// Eligibility inspection must not modify the draft.
		assertTrue(draft.isEmpty());
		assertTrue(draft.addWholeQuestion(section, partB, corpus));
		assertEquals(List.of(partA, partB, partC), draft.getQuestions(section));
		assertEquals(3, draft.getTotalSelectedCount());
		assertEquals(9, draft.getTotalMarks(section));
		PrintableAssessmentPlan plan = draft.buildPlan();
		assertEquals(1, plan.getPaperOneQuestionCount());
		assertEquals(List.of("1(a)", "1(b)", "1(c)"),
				plan.getPaperOne().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(9, plan.getTotalMarks());
		assertTrue(plan.getPaperOne().stream().allMatch(item -> item.question().hasAnswer()));
	}

	@Test
	void selectsStandaloneQuestionWithoutUnrelatedQuestions() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question first = createQuestion(10, booklet, "21", 3, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		Question second = createQuestion(20, booklet, "22", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		var section = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		List<Question> corpus = List.of(second, first);
		assertTrue(draft.addWholeQuestion(section, first, corpus));
		assertEquals(List.of(first), draft.getQuestions(section));
		assertEquals(3, draft.getTotalMarks(section));
		assertEquals(List.of(second), draft.eligibleWholeQuestion(section, second, corpus));
	}

	@Test
	void stagedSelectionsPreservePersistentExaminationIdentity() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		UUID originalIdentity = draft.getExaminationId();
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question question = createQuestion(10, booklet, "7", 1, QuestionResponseType.MULTIPLE_CHOICE, null, true);
		assertTrue(draft.addSelections(ExamBuilderDraft.Section.MULTIPLE_CHOICE, List.of(question), List.of(question)));
		assertEquals(originalIdentity, draft.getExaminationId());
		assertFalse(draft.isFinalised());
		draft.clear();
		assertEquals(originalIdentity, draft.getExaminationId());
		assertTrue(draft.isEmpty());
	}

	@Test
	void transfersEntireMultipartQuestionBetweenPapers() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		SourceQuestion source = new SourceQuestion(1, booklet, "21");
		Question partA = createQuestion(10, booklet, "21a", 2, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question partB = createQuestion(20, booklet, "21b", 3, QuestionResponseType.WRITTEN_RESPONSE, source, true);
		Question standalone = createQuestion(30, booklet, "25", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		List<Question> corpus = List.of(partA, partB, standalone);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		var multipleChoice = ExamBuilderDraft.Section.MULTIPLE_CHOICE;
		assertTrue(draft.addWholeQuestion(paperOne, partB, corpus));
		assertTrue(draft.addWholeQuestion(paperTwo, standalone, corpus));

		// An incompatible destination must not remove
		// any constituent part from the original section.
		assertFalse(draft.transfer(paperOne, multipleChoice, partB.getId()));
		assertEquals(List.of(partA, partB), draft.getQuestions(paperOne));
		assertTrue(draft.transfer(paperOne, paperTwo, partB.getId()));
		assertTrue(draft.getQuestions(paperOne).isEmpty());
		assertEquals(List.of(standalone, partA, partB), draft.getQuestions(paperTwo));
		assertEquals(0, draft.getTotalMarks(paperOne));
		assertEquals(9, draft.getTotalMarks(paperTwo));
		assertFalse(draft.addWholeQuestion(paperOne, partA, corpus));
		assertFalse(draft.addWholeQuestion(paperOne, partB, corpus));
		PrintableAssessmentPlan plan = draft.buildPlan();
		assertEquals(List.of("1", "2(a)", "2(b)"),
				plan.getPaperTwo().stream().map(PrintableQuestionNumbering.NumberedQuestion::label).toList());
		assertEquals(2, plan.getPaperTwoQuestionCount());
		assertEquals(9, plan.getTotalMarks());
	}

	@Test
	void transfersWrittenQuestionsBetweenPapers() {
		ExamBuilderDraft draft = new ExamBuilderDraft(chemistry);
		ExamBooklet booklet = createBooklet(chemistry, 1);
		Question written = createQuestion(1, booklet, "21", 4, QuestionResponseType.WRITTEN_RESPONSE, null, true);
		var paperOne = ExamBuilderDraft.Section.PAPER_ONE_WRITTEN;
		var paperTwo = ExamBuilderDraft.Section.PAPER_TWO_WRITTEN;
		assertTrue(draft.add(paperOne, written));
		assertFalse(draft.transfer(paperOne, ExamBuilderDraft.Section.MULTIPLE_CHOICE, written.getId()));
		assertEquals(List.of(written), draft.getQuestions(paperOne));
		assertTrue(draft.transfer(paperOne, paperTwo, written.getId()));
		assertTrue(draft.getQuestions(paperOne).isEmpty());
		assertEquals(List.of(written), draft.getQuestions(paperTwo));
		assertEquals(0, draft.getTotalMarks(paperOne));
		assertEquals(4, draft.getTotalMarks(paperTwo));
		assertEquals("1", draft.buildPlan().getPaperTwo().getFirst().label());
		draft.clear();
		assertTrue(draft.isEmpty());
	}

	private ExamBooklet createBooklet(Subject subject, long id) {
		Exam exam = new Exam(id, subject, new ExamProvider(id, "QCAA"), 2025, "External");
		return new ExamBooklet(id, exam, "Paper 1", new SourceDocument(id, "questions.pdf"));
	}

	private Question createQuestion(long id, ExamBooklet booklet, String code, int marks,
			QuestionResponseType responseType, SourceQuestion source, boolean answered) {
		Subject subject = booklet.getExam().getSubject();
		SyllabusVersion syllabus = new SyllabusVersion(1, subject, "2025", true);
		Unit unit = new Unit(1, syllabus, "3", "Unit 3", 1);
		Topic topic = new Topic(2, syllabus, unit, "3.1", "Equilibrium", 1);
		Subtopic subtopic = new Subtopic(3, syllabus, topic, "3.1.1", "Equilibrium", 1);
		Question question = new Question(id, booklet, code, "", marks,
				List.of(new QuestionRegion(booklet, 1, 0.1, 0.1, 0.8, 0.2)), subtopic, false, source, null,
				responseType);
		if (!answered) {
			return question;
		}
		if (responseType == QuestionResponseType.MULTIPLE_CHOICE) {
			question.setAnswer(new Answer(id, "C", List.of()));
		} else {
			AnswerFile answerFile = new AnswerFile(1, booklet.getExam(), "Marking guide",
					new SourceDocument(10, "answers.pdf"));
			question.setAnswer(new Answer(id, null, List.of(new AnswerRegion(answerFile, 1, 0.1, 0.1, 0.8, 0.2))));
		}
		return question;
	}
}
