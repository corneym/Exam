package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SqliteContributorAssignmentSessionRepository.Session;

/**
 * Resolves persisted contributor assignments into validated Dashboard context.
 *
 * <p>
 * The assignment session supplies the authoritative local Subject and Exam
 * identities. This resolver checks that both entities still exist and retain
 * their recorded ownership before they are exposed to the application.
 */
public final class ContributorAssignmentDashboardContextResolver {

	private final SqliteDatabase database;
	private final SqliteContributorAssignmentSessionRepository sessions;

	/**
	 * Creates a resolver for an initialised contributor database.
	 *
	 * @param database contributor SQLite database
	 * @throws NullPointerException if database is null
	 */
	public ContributorAssignmentDashboardContextResolver(SqliteDatabase database) {
		this.database = Objects.requireNonNull(database, "database");
		sessions = new SqliteContributorAssignmentSessionRepository(database);
	}

	/**
	 * Resolves the currently selected assignment, if any.
	 *
	 * @return validated Dashboard context, or empty in ordinary mode
	 * @throws SQLException if stored identities or persistence are invalid
	 */
	public Optional<Context> active() throws SQLException {
		Optional<Session> selected = sessions.active();
		if (selected.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(resolve(selected.get()));
	}

	/**
	 * Resolves a particular installed assignment without selecting it.
	 *
	 * @param assignmentId installed assignment identity
	 * @return validated Subject and Exam context
	 * @throws SQLException             if persistence or stored ownership is
	 *                                  invalid
	 * @throws IllegalArgumentException if the assignment is not installed
	 * @throws NullPointerException     if assignmentId is null
	 */
	public Context require(UUID assignmentId) throws SQLException {
		Objects.requireNonNull(assignmentId, "assignmentId");
		Session session = sessions.find(assignmentId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown contributor assignment: " + assignmentId));
		return resolve(session);
	}

	private Context resolve(Session session) throws SQLException {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT subject.subject_name
						FROM subjects subject
						JOIN exams exam
						    ON exam.subject_id = subject.id
						WHERE subject.id = ?
						  AND exam.id = ?
						""")) {
			statement.setLong(1, session.subjectLocalId());
			statement.setLong(2, session.examLocalId());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("Installed assignment Subject or Exam " + "is missing or inconsistent: "
							+ session.assignmentId());
				}
				Subject subject = new Subject(session.subjectLocalId(), result.getString("subject_name"));
				if (result.next()) {
					throw new SQLException("Duplicate Subject/Exam assignment context");
				}
				return new Context(session, subject);
			}
		}
	}

	/**
	 * Validated application context for one whole-Exam assignment.
	 *
	 * @param session original persisted assignment session
	 * @param subject locally installed Subject
	 */
	public record Context(Session session, Subject subject) {

		/**
		 * Validates the resolved assignment context.
		 *
		 * @throws NullPointerException     if either component is null
		 * @throws IllegalArgumentException if the Subject ID disagrees with the stored
		 *                                  session
		 */
		public Context {
			Objects.requireNonNull(session, "session");
			Objects.requireNonNull(subject, "subject");
			if (subject.getId() != session.subjectLocalId()) {
				throw new IllegalArgumentException("Assignment Subject identity mismatch");
			}
		}
	}
}
