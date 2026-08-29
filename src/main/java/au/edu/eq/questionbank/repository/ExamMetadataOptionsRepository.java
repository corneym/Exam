package au.edu.eq.questionbank.repository;

import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

public class ExamMetadataOptionsRepository {

	private static final String PROVIDERS = "providers";
	private static final String ASSESSMENTS = "assessments";
	private static final String BOOKLETS = "booklets";

	private final Preferences preferences = Preferences.userNodeForPackage(ExamMetadataOptionsRepository.class);

	public List<String> getProviders() {
		return getValues(PROVIDERS);
	}

	public List<String> getAssessments() {
		return getValues(ASSESSMENTS);
	}

	public List<String> getBooklets() {
		return getValues(BOOKLETS);
	}

	public void addProvider(String value) {
		addValue(PROVIDERS, value);
	}

	public void addAssessment(String value) {
		addValue(ASSESSMENTS, value);
	}

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
