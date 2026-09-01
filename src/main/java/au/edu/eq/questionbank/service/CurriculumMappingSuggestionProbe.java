package au.edu.eq.questionbank.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.ConfigurationException;
import au.edu.eq.questionbank.model.CurriculumLevel;
import au.edu.eq.questionbank.model.CurriculumMappingSuggestion;
import au.edu.eq.questionbank.model.CurriculumNode;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.SqliteDatabase;

/**
 * Diagnostic command that writes ranked Chemistry 2019-to-2025 descriptor
 * suggestions to a tab-separated file under {@code target}. This is a data probe,
 * not the subject-independent application workflow.
 */
public final class CurriculumMappingSuggestionProbe {
	private CurriculumMappingSuggestionProbe() {
	}

	/**
	 * Runs the fixed-version diagnostic against the configured application database.
	 * Failures are reported to standard error.
	 *
	 * @param args ignored command-line arguments
	 */
	public static void main(String[] args) {
		try {
			runProbe();
		} catch (ConfigurationException e) {
			System.err.println("Configuration error: " + e.getMessage());
		} catch (SQLException e) {
			System.err.println("Database error: " + e.getMessage());
		} catch (IOException e) {
			System.err.println("File error: " + e.getMessage());
		} catch (IllegalStateException e) {
			System.err.println("Cannot run mapping probe: " + e.getMessage());
		}
	}

	private static void runProbe() throws IOException, SQLException {
		ApplicationConfig config = ApplicationConfig.load(Path.of("questionbank.properties"));
		SqliteDatabase database = new SqliteDatabase(config.databasePath());
		database.initialiseSchema();
		SqliteCurriculumRepository repository = new SqliteCurriculumRepository(database);
		Subject chemistry = findSubject(repository, "Chemistry");
		SyllabusVersion syllabus2019 = findVersion(repository, chemistry, "2019");
		SyllabusVersion syllabus2025 = findVersion(repository, chemistry, "2025");
		List<CurriculumNode> sourceDescriptors = findDescriptors(repository, syllabus2019);
		TfIdfCurriculumMappingSuggester suggester = new TfIdfCurriculumMappingSuggester(repository);
		List<String> lines = new ArrayList<>();
		lines.add("source_code\tsource_text\trank\ttarget_code\ttarget_text\tscore");
		for (CurriculumNode source : sourceDescriptors) {
			List<CurriculumMappingSuggestion> suggestions = suggester.suggest(source, syllabus2025);
			for (int index = 0; index < suggestions.size(); index++) {
				CurriculumMappingSuggestion suggestion = suggestions.get(index);
				lines.add(source.getCode() + "\t" + clean(source.getName()) + "\t" + (index + 1) + "\t"
						+ suggestion.getTarget().getCode() + "\t" + clean(suggestion.getTarget().getName()) + "\t"
						+ String.format("%.4f", suggestion.getScore()));
			}
		}
		Path output = Path.of("target", "chemistry-mapping-suggestions.tsv");
		Files.createDirectories(output.getParent());
		Files.write(output, lines);
		System.out.println("2019 descriptors: " + sourceDescriptors.size());
		System.out.println("Written to: " + output.toAbsolutePath());
	}

	private static String clean(String text) {
		return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static void collectDescriptors(SqliteCurriculumRepository repository, CurriculumNode node,
			List<CurriculumNode> descriptors) {
		if (node.getLevel() == CurriculumLevel.DESCRIPTOR) {
			descriptors.add(node);
			return;
		}
		for (CurriculumNode child : repository.findChildren(node)) {
			collectDescriptors(repository, child, descriptors);
		}
	}

	private static List<CurriculumNode> findDescriptors(SqliteCurriculumRepository repository,
			SyllabusVersion syllabusVersion) {
		List<CurriculumNode> descriptors = new ArrayList<>();
		for (CurriculumNode root : repository.findRootNodes(syllabusVersion)) {
			collectDescriptors(repository, root, descriptors);
		}
		return descriptors;
	}

	private static Subject findSubject(SqliteCurriculumRepository repository, String name) {
		for (Subject subject : repository.findAllSubjects()) {
			if (name.equals(subject.getName())) {
				return subject;
			}
		}
		throw new IllegalStateException("Subject not found: " + name);
	}

	private static SyllabusVersion findVersion(SqliteCurriculumRepository repository, Subject subject, String name) {
		for (SyllabusVersion version : repository.findVersionsForSubject(subject)) {
			if (name.equals(version.getName())) {
				return version;
			}
		}
		throw new IllegalStateException("Syllabus version not found for " + subject.getName() + ": " + name);
	}
}
