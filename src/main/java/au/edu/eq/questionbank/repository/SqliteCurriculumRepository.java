package au.edu.eq.questionbank.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import au.edu.eq.questionbank.model.Subject;

public final class SqliteCurriculumRepository {

	private final SqliteDatabase database;

	public SqliteCurriculumRepository(SqliteDatabase database) {
		if (database == null) {
			throw new NullPointerException("database");
		}

		this.database = database;
	}

	public List<Subject> findAllSubjects() {
		List<Subject> subjects = new ArrayList<>();

		try (Connection connection = database.openConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT id, subject_name
						FROM subjects
						ORDER BY subject_name
						""")) {

			while (result.next()) {
				Subject subject = new Subject(result.getLong("id"), result.getString("subject_name"));
				subjects.add(subject);
			}
			return subjects;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read subjects from database", e);
		}
	}

	public Optional<Subject> findSubjectById(long id) {
		try (Connection connection = database.openConnection();
				PreparedStatement statement = connection.prepareStatement("""
						SELECT id, subject_name
						FROM subjects
						WHERE id = ?
						""")) {

			statement.setLong(1, id);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return Optional.empty();
				}

				Subject subject = new Subject(result.getLong("id"), result.getString("subject_name"));

				return Optional.of(subject);
			}

		} catch (SQLException e) {
			throw new IllegalStateException("Could not read subject from database", e);
		}
	}
}