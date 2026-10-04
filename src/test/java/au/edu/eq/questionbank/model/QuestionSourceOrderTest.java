package au.edu.eq.questionbank.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionSourceOrderTest {

	private final Subject chemistry = new Subject(1, "Chemistry");
	private final SyllabusVersion syllabus = new SyllabusVersion(2, chemistry, "2025", true);
	private final Unit unit = new Unit(3, syllabus, "1", "Unit 1", 1);
	private final Topic topic = new Topic(4, syllabus, unit, "1.1", "Topic 1", 1);
	private final Descriptor descriptor = new Descriptor(5, syllabus, topic, "1.1.1", "Descriptor 1", 1);

	@Test
	void naturallyOrdersExistingAlphabeticPrefixForm() {
		List<String> codes = new ArrayList<>(List.of("Q10", "Q3b", "Q3", "Q3a"));

		// Existing Q-prefixed codes use the same numeric and alphabetic-part ordering.
		codes.sort(QuestionSourceOrder::compareQuestionCodes);
		assertEquals(List.of("Q3", "Q3a", "Q3b", "Q10"), codes);
	}

	@Test
	void naturallyOrdersNumericQuestionsAndAlphabeticParts() {
		List<String> codes = new ArrayList<>(List.of("10", "3b", "3", "3a", "2"));

		// Sort the deliberately scrambled codes using the source-order Question syntax.
		codes.sort(QuestionSourceOrder::compareQuestionCodes);
		assertEquals(List.of("2", "3", "3a", "3b", "10"), codes);
	}

	@Test
	void sortsByProviderYearExamBookletAndNaturalQuestionCode() {
		ExamProvider alpha = new ExamProvider(10, "Alpha Authority");
		ExamProvider beta = new ExamProvider(11, "Beta Authority");
		Exam alpha2023External = new Exam(20, chemistry, alpha, 2023, "External Assessment");
		Exam alpha2023Trial = new Exam(21, chemistry, alpha, 2023, "Trial Assessment");
		Exam alpha2024External = new Exam(22, chemistry, alpha, 2024, "External Assessment");
		Exam beta2022External = new Exam(23, chemistry, beta, 2022, "External Assessment");
		ExamBooklet alpha2023ExternalPaper1 = booklet(30, alpha2023External, "Paper 1",
				"alpha-2023-external-paper1.pdf");
		ExamBooklet alpha2023ExternalPaper2 = booklet(31, alpha2023External, "Paper 2",
				"alpha-2023-external-paper2.pdf");
		ExamBooklet alpha2023TrialPaper1 = booklet(32, alpha2023Trial, "Paper 1", "alpha-2023-trial-paper1.pdf");
		ExamBooklet alpha2024Paper1 = booklet(33, alpha2024External, "Paper 1", "alpha-2024-paper1.pdf");
		ExamBooklet beta2022Paper1 = booklet(34, beta2022External, "Paper 1", "beta-2022-paper1.pdf");
		Question alpha2023Question10 = question(40, alpha2023ExternalPaper1, "10");
		Question alpha2023Question22b = question(41, alpha2023ExternalPaper1, "22b");
		Question alpha2023Question1 = question(42, alpha2023ExternalPaper1, "1");
		Question alpha2023Question22 = question(43, alpha2023ExternalPaper1, "22");
		Question alpha2023Question22a = question(44, alpha2023ExternalPaper1, "22a");
		Question alpha2023ExternalPaper2Question1 = question(45, alpha2023ExternalPaper2, "1");
		Question alpha2023TrialQuestion1 = question(46, alpha2023TrialPaper1, "1");
		Question alpha2024Question1 = question(47, alpha2024Paper1, "1");
		Question beta2022Question1 = question(48, beta2022Paper1, "1");
		List<Question> questions = new ArrayList<>(List.of(beta2022Question1, alpha2023TrialQuestion1,
				alpha2024Question1, alpha2023Question22b, alpha2023ExternalPaper2Question1, alpha2023Question10,
				alpha2023Question22a, alpha2023Question1, alpha2023Question22));

		// Complete source order groups provider, year, Exam and booklet before applying
		// natural numeric/alphabetic Question-code ordering.
		questions.sort(QuestionSourceOrder.comparator());
		assertEquals(List.of(alpha2023Question1, alpha2023Question10, alpha2023Question22, alpha2023Question22a,
				alpha2023Question22b, alpha2023ExternalPaper2Question1, alpha2023TrialQuestion1, alpha2024Question1,
				beta2022Question1), questions);
	}

	private ExamBooklet booklet(long id, Exam exam, String name, String relativePath) {

		// Give each test booklet its own persisted source-document identity.
		return new ExamBooklet(id, exam, name, new SourceDocument(id + 100, relativePath));
	}

	private Question question(long id, ExamBooklet booklet, String questionCode) {

		// Source-order tests need only Question metadata, so zero source regions are
		// sufficient for these legacy-compatible Question instances.
		return new Question(id, booklet, questionCode, "", 1, List.of(), descriptor, false, null, null,
				QuestionResponseType.WRITTEN_RESPONSE);
	}
}
