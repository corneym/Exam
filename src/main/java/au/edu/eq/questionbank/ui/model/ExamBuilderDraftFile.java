package au.edu.eq.questionbank.ui.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.pdf.PrintableExamSettings;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;

/**
 * Saves and reloads examination drafts using portable Subject and Question
 * identities.
 *
 * <p>
 * Draft files contain examination metadata and ordered references to Questions.
 * They do not contain managed Question Bank content or modify authoritative
 * records.
 */
public final class ExamBuilderDraftFile {

	private static final int FORMAT_VERSION = 3;
	private final ObjectMapper mapper = new ObjectMapper();
	private final LongFunction<UUID> questionIdentity;
	private final Function<UUID, Optional<Question>> questionLookup;
	private final LongFunction<UUID> subjectIdentity;
	private final Supplier<List<Question>> corpusSupplier;
	private final SqliteMockExamStore mockExamStore;

	/**
	 * Creates a portable file service supporting editable examination drafts.
	 *
	 * @param identities portable identity repository
	 * @param questions  Question repository
	 * @throws NullPointerException if either repository is null
	 */
	public ExamBuilderDraftFile(SqlitePortableIdentityRepository identities, SqliteQuestionRepository questions) {
		this(identities, questions, null);
	}

	/**
	 * Creates a portable file service with authoritative lifecycle resolution.
	 *
	 * @param identities    portable identity repository
	 * @param questions     Question repository
	 * @param mockExamStore managed examination store, or null for draft-only
	 *                      operation
	 * @throws NullPointerException if identities or questions is null
	 */
	public ExamBuilderDraftFile(SqlitePortableIdentityRepository identities, SqliteQuestionRepository questions,
			SqliteMockExamStore mockExamStore) {
		Objects.requireNonNull(identities, "identities");
		Objects.requireNonNull(questions, "questions");
		this.subjectIdentity = id -> requireIdentity(identities, EntityType.SUBJECT, id);
		this.questionIdentity = id -> requireIdentity(identities, EntityType.QUESTION, id);
		this.corpusSupplier = questions::findAll;
		this.mockExamStore = mockExamStore;
		this.questionLookup = portableId -> {
			try {
				return identities.findByPortableId(EntityType.QUESTION, portableId)
						.flatMap(identity -> questions.findById(identity.localId()));
			} catch (SQLException failure) {
				throw new IllegalStateException("Cannot resolve Question identity: " + portableId, failure);
			}
		};
	}

	ExamBuilderDraftFile(LongFunction<UUID> subjectIdentity, LongFunction<UUID> questionIdentity,
			Function<UUID, Optional<Question>> questionLookup) {
		this(subjectIdentity, questionIdentity, questionLookup, null);
	}

	ExamBuilderDraftFile(LongFunction<UUID> subjectIdentity, LongFunction<UUID> questionIdentity,
			Function<UUID, Optional<Question>> questionLookup, Supplier<List<Question>> corpusSupplier) {
		this.subjectIdentity = Objects.requireNonNull(subjectIdentity, "subjectIdentity");
		this.questionIdentity = Objects.requireNonNull(questionIdentity, "questionIdentity");
		this.questionLookup = Objects.requireNonNull(questionLookup, "questionLookup");
		this.corpusSupplier = corpusSupplier;
		this.mockExamStore = null;
	}

	private static int readInteger(JsonNode object, String field) throws IOException {
		JsonNode value = object.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IOException("Invalid examination draft value: " + field);
		}
		return value.intValue();
	}

	private static UUID readUuid(JsonNode value, String description) throws IOException {
		if (!value.isTextual()) {
			throw new IOException("Missing " + description);
		}
		try {
			return UUID.fromString(value.asText());
		} catch (IllegalArgumentException failure) {
			throw new IOException("Invalid " + description, failure);
		}
	}

	private static UUID requireIdentity(SqlitePortableIdentityRepository identities, EntityType type, long id) {
		try {
			return identities.requireByLocalId(type, id).portableId();
		} catch (SQLException failure) {
			throw new IllegalStateException("Cannot resolve " + type + " identity: " + id, failure);
		}
	}

	/**
	 * Loads an examination file against the current Working Subject.
	 *
	 * <p>
	 * Version-one files receive a new examination UUID. Versions one and two
	 * default to Paper 2 included. Version-three files preserve the explicit Paper
	 * 2 setting. An authoritative finalised definition supersedes a stale external
	 * file with the same examination UUID.
	 *
	 * @param file           examination file
	 * @param workingSubject Working Subject
	 * @return validated examination metadata, selections and paper inclusion
	 * @throws IOException          if the file, references or lifecycle are invalid
	 * @throws NullPointerException if workingSubject is null
	 */
	public LoadedDraft load(Path file, Subject workingSubject) throws IOException {
		Objects.requireNonNull(workingSubject, "workingSubject");
		try {
			JsonNode root;
			try (InputStream input = Files.newInputStream(file)) {
				root = mapper.readTree(input);
			}
			if (root == null || !root.isObject()) {
				throw new IOException("Not a valid examination draft.");
			}
			int version = readInteger(root, "version");
			if (version < 1 || version > FORMAT_VERSION) {
				throw new IOException("Unsupported examination draft version.");
			}
			UUID savedSubject = readUuid(root.path("subjectId"), "Subject identity");
			UUID currentSubject = subjectIdentity.apply(workingSubject.getId());
			if (!savedSubject.equals(currentSubject)) {
				throw new IOException("This examination belongs to another Subject.");
			}
			UUID examinationId = version == 1 ? UUID.randomUUID()
					: readUuid(root.path("examinationId"), "Examination identity");
			if (version >= 2) {
				JsonNode lifecycle = root.path("finalised");
				if (!lifecycle.isBoolean()) {
					throw new IOException("Invalid examination lifecycle status.");
				}
				if (mockExamStore != null) {
					Optional<SqliteMockExamStore.StoredExam> committed = mockExamStore.findFinalised(examinationId,
							workingSubject);
					if (committed.isPresent()) {

						// The managed definition is authoritative,
						// including its Paper 2 inclusion setting.
						SqliteMockExamStore.StoredExam stored = committed.orElseThrow();
						return new LoadedDraft(stored.draft(), stored.settings(), stored.includePaperTwo());
					}
				}
				if (lifecycle.booleanValue()) {
					throw new IOException(
							"The authoritative finalised examination " + "is not available in this database.");
				}
			}
			boolean includePaperTwo = true;
			if (version >= 3) {
				JsonNode inclusion = root.path("includePaperTwo");
				if (!inclusion.isBoolean()) {
					throw new IOException("Invalid Paper 2 inclusion setting.");
				}
				includePaperTwo = inclusion.booleanValue();
			}
			JsonNode metadata = root.path("settings");
			PrintableExamSettings settings = new PrintableExamSettings(workingSubject.getName(),
					readInteger(metadata, "year"), readInteger(metadata, "paperOnePerusalMinutes"),
					readInteger(metadata, "paperOneWorkingMinutes"), readInteger(metadata, "paperTwoPerusalMinutes"),
					readInteger(metadata, "paperTwoWorkingMinutes"));
			JsonNode sections = root.path("sections");
			if (!sections.isObject()) {
				throw new IOException("Examination sections are missing.");
			}
			ExamBuilderDraft loaded = new ExamBuilderDraft(workingSubject, examinationId);
			List<Question> corpus = corpusSupplier == null ? null : List.copyOf(corpusSupplier.get());
			for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
				loadSection(loaded, section, sections.path(section.name()), corpus);
			}
			if (!includePaperTwo && !loaded.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN).isEmpty()) {
				throw new IOException("Paper 2 is excluded but contains Questions.");
			}
			return new LoadedDraft(loaded, settings, includePaperTwo);
		} catch (SQLException | IllegalArgumentException | IllegalStateException failure) {
			throw new IOException("Cannot reopen examination draft: " + failure.getMessage(), failure);
		}
	}

	/**
	 * Saves a portable examination file with Paper 2 included.
	 *
	 * <p>
	 * Retained for compatibility with callers using the original examination
	 * structure.
	 *
	 * @param file     destination examination file
	 * @param draft    ordered examination selections
	 * @param settings examination metadata
	 * @throws IOException              if encoding or publication fails
	 * @throws IllegalArgumentException if the settings Subject differs
	 * @throws NullPointerException     if a required argument is null
	 */
	public void save(Path file, ExamBuilderDraft draft, PrintableExamSettings settings) throws IOException {
		save(file, draft, settings, true);
	}

	/**
	 * Saves a portable examination file with explicit paper inclusion.
	 *
	 * <p>
	 * Preserves the examination UUID, lifecycle and Paper 2 setting. File
	 * publication does not create or release annual reservations.
	 *
	 * @param file            destination examination file
	 * @param draft           ordered examination selections
	 * @param settings        examination metadata
	 * @param includePaperTwo whether Paper 2 is included
	 * @throws IOException              if encoding or publication fails
	 * @throws IllegalArgumentException if the settings Subject differs or Paper 2
	 *                                  is excluded but contains Questions
	 * @throws NullPointerException     if a required argument is null
	 */
	public void save(Path file, ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo)
			throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(draft, "draft");
		Objects.requireNonNull(settings, "settings");
		if (!draft.getSubject().getName().equals(settings.subject())) {
			throw new IllegalArgumentException("Draft and settings Subjects differ.");
		}
		if (!includePaperTwo && !draft.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN).isEmpty()) {
			throw new IllegalArgumentException("Paper 2 is excluded but contains Questions.");
		}
		ObjectNode root = mapper.createObjectNode();
		try {
			UUID portableSubject = subjectIdentity.apply(draft.getSubject().getId());
			root.put("version", FORMAT_VERSION);
			root.put("examinationId", draft.getExaminationId().toString());
			root.put("subjectId", portableSubject.toString());
			root.put("finalised", draft.isFinalised());
			root.put("includePaperTwo", includePaperTwo);
			ObjectNode metadata = root.putObject("settings");
			metadata.put("year", settings.year());
			metadata.put("paperOnePerusalMinutes", settings.paperOnePerusalMinutes());
			metadata.put("paperOneWorkingMinutes", settings.paperOneWorkingMinutes());
			metadata.put("paperTwoPerusalMinutes", settings.paperTwoPerusalMinutes());
			metadata.put("paperTwoWorkingMinutes", settings.paperTwoWorkingMinutes());
			ObjectNode sections = root.putObject("sections");
			for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
				ArrayNode identifiers = sections.putArray(section.name());
				for (Question question : draft.getQuestions(section)) {
					UUID portableId = questionIdentity.apply(question.getId());
					identifiers.add(portableId.toString());
				}
			}
		} catch (IllegalStateException failure) {
			throw new IOException("Cannot resolve draft identities: " + failure.getMessage(), failure);
		}
		Path destination = file.toAbsolutePath().normalize();
		Path temporary = Files.createTempFile(destination.getParent(), "exam-draft-", ".tmp");
		try {
			mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);

			// Publish only a successfully encoded draft.
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException failure) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private void loadSection(ExamBuilderDraft loaded, ExamBuilderDraft.Section section, JsonNode identifiers,
			List<Question> corpus) throws IOException {
		if (!identifiers.isArray()) {
			throw new IOException("Missing examination section: " + section);
		}
		List<Question> savedQuestions = new ArrayList<>();
		for (JsonNode identifier : identifiers) {
			UUID portableId = readUuid(identifier, "Question identity");
			Question question = questionLookup.apply(portableId)
					.orElseThrow(() -> new IOException("Question no longer exists: " + portableId));
			savedQuestions.add(question);
		}
		if (savedQuestions.isEmpty()) {
			return;
		}

		// The package-visible standalone test boundary must
		// not manufacture a complete multipart corpus from
		// only the identifiers present in a saved draft.
		List<Question> authoritativeCorpus = corpus == null
				? savedQuestions.stream().filter(question -> question.getSourceQuestion() == null).toList()
				: corpus;
		if (!loaded.addSelections(section, savedQuestions, authoritativeCorpus)) {
			throw new IOException("A Question is no longer eligible or is duplicated " + "in section: " + section);
		}
		List<Long> savedIds = savedQuestions.stream().map(Question::getId).toList();
		List<Long> validatedIds = loaded.getQuestions(section).stream().map(Question::getId).toList();

		// Reject older partial or incorrectly ordered selections.
		// Reopening must never silently change examination content.
		if (!savedIds.equals(validatedIds)) {
			throw new IOException("The saved draft contains an incomplete or "
					+ "incorrectly ordered multipart Question " + "in section: " + section);
		}
	}

	/**
	 * Successfully reconstructed examination and its metadata.
	 *
	 * @param draft           examination selections and lifecycle state
	 * @param settings        loaded examination metadata
	 * @param includePaperTwo whether Paper 2 is included
	 */
	public record LoadedDraft(ExamBuilderDraft draft, PrintableExamSettings settings, boolean includePaperTwo) {

		/**
		 * Creates a reconstructed examination result.
		 *
		 * @param draft           reconstructed examination
		 * @param settings        loaded examination metadata
		 * @param includePaperTwo whether Paper 2 is included
		 * @throws NullPointerException     if draft or settings is null
		 * @throws IllegalArgumentException if Paper 2 is excluded but contains
		 *                                  Questions
		 */
		public LoadedDraft {
			Objects.requireNonNull(draft, "draft");
			Objects.requireNonNull(settings, "settings");
			if (!includePaperTwo && !draft.getQuestions(ExamBuilderDraft.Section.PAPER_TWO_WRITTEN).isEmpty()) {
				throw new IllegalArgumentException("Paper 2 is excluded but contains Questions.");
			}
		}

		/**
		 * Creates a reconstructed examination with Paper 2 included.
		 *
		 * @param draft    reconstructed examination
		 * @param settings loaded examination metadata
		 * @throws NullPointerException if either argument is null
		 */
		public LoadedDraft(ExamBuilderDraft draft, PrintableExamSettings settings) {
			this(draft, settings, true);
		}
	}
}
