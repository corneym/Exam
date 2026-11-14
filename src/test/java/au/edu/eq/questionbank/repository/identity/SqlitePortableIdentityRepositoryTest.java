package au.edu.eq.questionbank.repository.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.Subtopic;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.model.Topic;
import au.edu.eq.questionbank.model.Unit;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteQuestionWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumWriter;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.Identity;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

class SqlitePortableIdentityRepositoryTest {

	@TempDir
	Path tempDirectory;

	@Test
	void databaseRollbackAlsoRollsBackIdentityCreation() throws Exception {
		SqliteDatabase database = createDatabase("rollback.db");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects (id, subject_name)
						VALUES (7, 'Biology')
						""");
				try (ResultSet result = statement.executeQuery("""
						SELECT portable_id
						FROM portable_entity_identities
						WHERE entity_type = 'SUBJECT'
						  AND local_id = 7
						""")) {
					assertTrue(result.next());
					assertEquals(4, UUID.fromString(result.getString("portable_id")).version());
				}
				connection.rollback();
			}
		}
		assertTrue(identities.findByLocalId(EntityType.SUBJECT, 7).isEmpty());
		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT COUNT(*)
						FROM subjects
						WHERE id = 7
						""")) {
			assertTrue(result.next());
			assertEquals(0, result.getInt(1));
		}
	}

	@Test
	void deletingAndReusingLocalIdPreservesRetiredPortableIdentity() throws Exception {
		SqliteDatabase database = createDatabase("reuse.db");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject original = writer.insertSubject("Biology");
		Identity originalIdentity = identities.requireByLocalId(EntityType.SUBJECT, original.getId());
		try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DELETE FROM subjects
					WHERE subject_name = 'Biology'
					""");

			// The integer key is deliberately reused. The new Subject
			// must still receive an unrelated portable UUID.
			statement.execute("""
					INSERT INTO subjects (id, subject_name)
					VALUES (1, 'Chemistry')
					""");
		}
		Identity replacement = identities.requireByLocalId(EntityType.SUBJECT, 1);
		assertNotEquals(originalIdentity.portableId(), replacement.portableId());
		assertTrue(identities.findByPortableId(EntityType.SUBJECT, originalIdentity.portableId()).isEmpty());
		assertEquals(1, retiredIdentityCount(database, originalIdentity.portableId()));
		assertEquals(0, retiredIdentityCount(database, replacement.portableId()));
	}

	@Test
	void existingPersistenceWritersAutomaticallyCreateIdentities() throws Exception {
		SqliteDatabase database = createDatabase("writers.db");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		SqliteCurriculumWriter curriculum = new SqliteCurriculumWriter(database);
		SqliteExamWriter examinations = new SqliteExamWriter(database);
		SqliteQuestionWriter questions = new SqliteQuestionWriter(database);
		Subject subject = curriculum.insertSubject("Chemistry");
		SyllabusVersion syllabus = curriculum.insertSyllabusVersion(subject, "2025", true);
		Unit unit = curriculum.insertUnit(syllabus, "1", "Unit 1", 0);
		Topic topic = curriculum.insertTopic(unit, "1.1", "Topic 1", 0);
		Subtopic subtopic = curriculum.insertSubtopic(topic, "1.1.1", "Subtopic 1", 0);
		Exam exam = examinations.createExam(subject, "QCAA", 2025, "External Assessment");
		SourceDocument source = examinations.insertSourceDocument("subjects/Chemistry/exams/QCAA/2025/questions.pdf");
		ExamBooklet booklet = examinations.insertExamBooklet(exam, source, "Paper 1");
		Question question = questions.insertQuestion(booklet, "1", "Example question", 1, List.of(), subtopic, false);
		Identity subjectIdentity = identities.requireByLocalId(EntityType.SUBJECT, subject.getId());
		Identity examIdentity = identities.requireByLocalId(EntityType.EXAM, exam.getId());
		Identity questionIdentity = identities.requireByLocalId(EntityType.QUESTION, question.getId());
		assertNotEquals(subjectIdentity.portableId(), examIdentity.portableId());
		assertNotEquals(examIdentity.portableId(), questionIdentity.portableId());
		assertEquals(questionIdentity,
				identities.findByPortableId(EntityType.QUESTION, questionIdentity.portableId()).orElseThrow());
		assertEquals(1, identities.findAllByLocalId(EntityType.EXAM).size());
		assertEquals(4, UUID.fromString(questionIdentity.revisionId().toString()).version());

		// A normal restart neither changes the installation UUID
		// nor regenerates an existing entity's revision.
		UUID installationId = identities.installationId();
		database.initialiseSchema();
		assertEquals(installationId, identities.installationId());
		assertEquals(questionIdentity, identities.requireByLocalId(EntityType.QUESTION, question.getId()));
		database.verifyIntegrity();
	}

	@Test
	void identityAndDomainPrimaryKeysCannotBeReassigned() throws Exception {
		SqliteDatabase database = createDatabase("immutable.db");
		SqliteCurriculumWriter writer = new SqliteCurriculumWriter(database);
		Subject subject = writer.insertSubject("Chemistry");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID originalUuid = identities.requireByLocalId(EntityType.SUBJECT, subject.getId()).portableId();
		try (Connection connection = database.openConnection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
					UPDATE portable_entity_identities
					SET portable_id = ?
					WHERE entity_type = 'SUBJECT'
					  AND local_id = ?
					""")) {
				statement.setString(1, UUID.randomUUID().toString());
				statement.setLong(2, subject.getId());
				assertThrows(SQLException.class, statement::executeUpdate);
			}
			try (Statement statement = connection.createStatement()) {
				assertThrows(SQLException.class, () -> statement.executeUpdate("""
						UPDATE subjects
						SET id = 999
						WHERE id = 1
						"""));
			}
		}
		assertEquals(originalUuid, identities.requireByLocalId(EntityType.SUBJECT, subject.getId()).portableId());
	}

	/**
	 * Verifies that portable identities generated by database triggers can be
	 * resolved before commit and disappear after rollback.
	 */
	@Test
	void newIdentitiesAreVisibleWithinTheInsertingTransaction() throws Exception {
		SqliteDatabase database = createDatabase("transaction-lookup.db");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		UUID insertedPortableId;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects (id, subject_name)
						VALUES (77, 'Biology')
						""");
			}
			Identity inserted = identities.findByLocalId(connection, EntityType.SUBJECT, 77).orElseThrow();
			insertedPortableId = inserted.portableId();
			assertEquals(77, inserted.localId());
			assertEquals(inserted,
					identities.findByPortableId(connection, EntityType.SUBJECT, insertedPortableId).orElseThrow());
			assertTrue(!connection.isClosed());
			connection.rollback();
		}
		assertTrue(identities.findByLocalId(EntityType.SUBJECT, 77).isEmpty());
		assertTrue(identities.findByPortableId(EntityType.SUBJECT, insertedPortableId).isEmpty());
		database.verifyIntegrity();
	}

	/**
	 * Verifies a new canonical identity and its approved origin alias can be
	 * committed by the same SQLite transaction.
	 */
	@Test
	void newIdentityAndOriginAliasCommitAtomically() throws Exception {
		SqliteDatabase database = createDatabase("atomic-identity.db");
		SqlitePortableIdentityRepository identities = new SqlitePortableIdentityRepository(database);
		SqlitePortableAliasRepository aliases = new SqlitePortableAliasRepository(database);
		UUID originInstallation = UUID.randomUUID();
		UUID incomingSubject = UUID.randomUUID();
		UUID canonical;
		try (Connection connection = database.openConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						INSERT INTO subjects (id, subject_name)
						VALUES (50, 'Biology')
						""");
			}
			Identity inserted = identities.findByLocalId(connection, EntityType.SUBJECT, 50).orElseThrow();
			canonical = inserted.portableId();
			aliases.approveAlias(connection, originInstallation, EntityType.SUBJECT, incomingSubject, canonical,
					"Approved import of new Biology Subject");
			assertEquals(canonical, aliases
					.findAlias(connection, originInstallation, EntityType.SUBJECT, incomingSubject).orElseThrow());
			connection.commit();
		}
		assertEquals(canonical, identities.requireByLocalId(EntityType.SUBJECT, 50).portableId());
		assertEquals(canonical,
				aliases.findAlias(originInstallation, EntityType.SUBJECT, incomingSubject).orElseThrow());
		database.verifyIntegrity();
	}

	private SqliteDatabase createDatabase(String name) throws SQLException {
		SqliteDatabase database = new SqliteDatabase(tempDirectory.resolve(name));
		database.initialiseSchema();
		return database;
	}

	private int retiredIdentityCount(SqliteDatabase database, UUID portableId) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT COUNT(*)
						FROM portable_entity_retirements
						WHERE portable_id = ?
						""")) {
			statement.setString(1, portableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getInt(1);
			}
		}
	}
}
