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

	private static final int FORMAT_VERSION = 1;
	private final ObjectMapper mapper = new ObjectMapper();
	private final LongFunction<UUID> questionIdentity;
	private final Function<UUID, Optional<Question>> questionLookup;
	private final LongFunction<UUID> subjectIdentity;
	private final Supplier<List<Question>> corpusSupplier;

	/**
	 * Creates a draft-file service backed by the existing portable identity and
	 * Question repositories.
	 *
	 * @param identities portable identity repository
	 * @param questions  Question repository
	 * @throws NullPointerException if either repository is null
	 */
	public ExamBuilderDraftFile(SqlitePortableIdentityRepository identities, SqliteQuestionRepository questions) {
		Objects.requireNonNull(identities, "identities");
		Objects.requireNonNull(questions, "questions");
		this.subjectIdentity = id -> requireIdentity(identities, EntityType.SUBJECT, id);
		this.questionIdentity = id -> requireIdentity(identities, EntityType.QUESTION, id);
		this.corpusSupplier = questions::findAll;
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
	 * Loads a draft against the current authoritative Subject.
	 *
	 * <p>
	 * All references and complete multipart groups are resolved and validated
	 * before the loaded draft is returned. Incomplete or incorrectly ordered
	 * multipart selections are rejected without changing the existing examination.
	 *
	 * @param file           examination draft file
	 * @param workingSubject authoritative Working Subject
	 * @return loaded examination metadata and selections
	 * @throws IOException          if the file is invalid, references cannot be
	 *                              resolved or selections are no longer eligible
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
			if (readInteger(root, "version") != FORMAT_VERSION) {
				throw new IOException("Unsupported examination draft version.");
			}
			UUID savedSubject = readUuid(root.path("subjectId"), "Subject identity");
			UUID currentSubject = subjectIdentity.apply(workingSubject.getId());
			if (!savedSubject.equals(currentSubject)) {
				throw new IOException("This examination belongs to another Subject.");
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
			ExamBuilderDraft loaded = new ExamBuilderDraft(workingSubject);
			List<Question> corpus = corpusSupplier == null ? null : List.copyOf(corpusSupplier.get());
			for (ExamBuilderDraft.Section section : ExamBuilderDraft.Section.values()) {
				loadSection(loaded, section, sections.path(section.name()), corpus);
			}
			return new LoadedDraft(loaded, settings);
		} catch (IllegalArgumentException | IllegalStateException failure) {
			throw new IOException("Cannot reopen examination draft: " + failure.getMessage(), failure);
		}
	}

	/**
	 * Saves the current examination draft.
	 *
	 * <p>
	 * Existing destination files are replaced only after the new draft has been
	 * successfully encoded and written to a temporary file in the same directory.
	 *
	 * @param file     destination draft file
	 * @param draft    ordered examination selections
	 * @param settings examination metadata
	 * @throws IOException              if the draft cannot be encoded or saved
	 * @throws IllegalArgumentException if the settings Subject differs from the
	 *                                  draft
	 */
	public void save(Path file, ExamBuilderDraft draft, PrintableExamSettings settings) throws IOException {
		Objects.requireNonNull(draft, "draft");
		Objects.requireNonNull(settings, "settings");
		if (!draft.getSubject().getName().equals(settings.subject())) {
			throw new IllegalArgumentException("Draft and settings Subjects differ.");
		}
		ObjectNode root = mapper.createObjectNode();
		try {
			UUID portableSubject = subjectIdentity.apply(draft.getSubject().getId());
			root.put("version", FORMAT_VERSION);
			root.put("subjectId", portableSubject.toString());
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

			// Publish only a complete, successfully encoded file.
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
	 * Successfully resolved examination draft and metadata.
	 *
	 * @param draft    loaded editable examination selections
	 * @param settings loaded examination metadata
	 */
	public record LoadedDraft(ExamBuilderDraft draft, PrintableExamSettings settings) {

		/**
		 * Creates a loaded draft result.
		 *
		 * @param draft    loaded examination selections
		 * @param settings loaded examination metadata
		 * @throws NullPointerException if either argument is null
		 */
		public LoadedDraft {
			Objects.requireNonNull(draft, "draft");
			Objects.requireNonNull(settings, "settings");
		}
	}
}
