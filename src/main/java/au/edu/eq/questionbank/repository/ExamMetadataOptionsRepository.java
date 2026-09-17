package au.edu.eq.questionbank.repository;

import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Stores reusable exam-provider, assessment, and booklet labels in the current
 * user's Java preferences. Values are returned in case-insensitive sorted order
 * and duplicate labels are ignored case-insensitively.
 */
public class ExamMetadataOptionsRepository {

	/**
	 * Creates a repository backed by the current user's saved examination labels.
	 */
	public ExamMetadataOptionsRepository() {
	}

	private static final String PROVIDERS = "providers";
	private static final String ASSESSMENTS = "assessments";
	private static final String BOOKLETS = "booklets";

	private final Preferences preferences = Preferences.userNodeForPackage(ExamMetadataOptionsRepository.class);

	/**
	 * Returns the stored provider labels.
	 *
	 * @return previously stored provider labels
	 */
	public List<String> getProviders() {
		return getValues(PROVIDERS);
	}

	/**
	 * Returns the stored assessment labels.
	 *
	 * @return previously stored assessment labels
	 */
	public List<String> getAssessments() {
		return getValues(ASSESSMENTS);
	}

	/**
	 * Returns the stored booklet labels.
	 *
	 * @return previously stored booklet labels
	 */
	public List<String> getBooklets() {
		return getValues(BOOKLETS);
	}

	/**
	 * Adds a non-blank provider label when it is not already stored.
	 *
	 * @param value the provider label
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public void addProvider(String value) {
		addValue(PROVIDERS, value);
	}

	/**
	 * Adds a non-blank assessment label when it is not already stored.
	 *
	 * @param value the assessment label
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public void addAssessment(String value) {
		addValue(ASSESSMENTS, value);
	}

	/**
	 * Adds a non-blank booklet label when it is not already stored.
	 *
	 * @param value the booklet label
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public void addBooklet(String value) {
		addValue(BOOKLETS, value);
	}

	private List<String> getValues(String key) {
		String stored = preferences.get(key, "");

		if (stored.isBlank()) {
			return List.of();
		}

		String[] values = stored.split("\\|");
		Arrays.sort(values, String.CASE_INSENSITIVE_ORDER);
		return List.copyOf(Arrays.asList(values));
	}

	private void addValue(String key, String value) {
		String trimmed = value.trim();

		if (trimmed.isBlank()) {
			return;
		}

		List<String> values = getValues(key);

		boolean alreadyExists = false;
		for (String existing : values) {
			if (existing.equalsIgnoreCase(trimmed)) {
				alreadyExists = true;
				break;
			}
		}

		if (alreadyExists) {
			return;
		}

		String updated = values.isEmpty() ? trimmed : String.join("|", values) + "|" + trimmed;

		preferences.put(key, updated);
	}
}
