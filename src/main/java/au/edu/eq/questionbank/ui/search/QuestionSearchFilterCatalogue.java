package au.edu.eq.questionbank.ui.search;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.ExamProvider;
import au.edu.eq.questionbank.model.Question;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;

/**
 * Immutable choices for Search metadata controls.
 *
 * Built off the JavaFX application thread from stored Questions, constrained by
 * Working Subject and Dashboard launch narrowing.
 */
record QuestionSearchFilterCatalogue(List<ExamProvider> providers, List<Integer> years, List<Exam> exams,
		List<ExamBooklet> booklets, List<SyllabusVersion> originalSyllabuses) {

	QuestionSearchFilterCatalogue {
		providers = List.copyOf(providers);
		years = List.copyOf(years);
		exams = List.copyOf(exams);
		booklets = List.copyOf(booklets);
		originalSyllabuses = List.copyOf(originalSyllabuses);
	}

	static QuestionSearchFilterCatalogue fromQuestions(List<Question> questions, Subject workingSubject,
			QuestionSearchNarrowing narrowing) {
		Objects.requireNonNull(questions, "questions");
		Objects.requireNonNull(workingSubject, "workingSubject");
		Objects.requireNonNull(narrowing, "narrowing");
		Map<Long, ExamProvider> providers = new HashMap<>();
		Map<Long, Exam> exams = new HashMap<>();
		Map<Long, ExamBooklet> booklets = new HashMap<>();
		Map<Long, SyllabusVersion> versions = new HashMap<>();
		TreeSet<Integer> years = new TreeSet<>(Comparator.reverseOrder());
		for (Question question : questions) {
			Exam exam = question.getExam();

			// Choices may never extend beyond the authoritative
			// Subject or an existing Dashboard launch restriction.
			if (!workingSubject.equals(exam.getSubject()) || !narrowing.includes(question)) {
				continue;
			}
			providers.putIfAbsent(exam.getProvider().getId(), exam.getProvider());
			years.add(exam.getYear());
			exams.putIfAbsent(exam.getId(), exam);
			ExamBooklet booklet = question.getBooklet();
			booklets.putIfAbsent(booklet.getId(), booklet);
			SyllabusVersion version = question.getClassification().getSyllabusVersion();
			versions.putIfAbsent(version.getId(), version);
		}
		List<ExamProvider> orderedProviders = providers.values().stream().sorted(Comparator
				.comparing(ExamProvider::getName, String.CASE_INSENSITIVE_ORDER).thenComparingLong(ExamProvider::getId))
				.toList();
		List<Exam> orderedExams = exams.values().stream()
				.sorted(Comparator.comparingInt(Exam::getYear).reversed()
						.thenComparing(e -> e.getProvider().getName(), String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Exam::getName, String.CASE_INSENSITIVE_ORDER).thenComparingLong(Exam::getId))
				.toList();
		List<ExamBooklet> orderedBooklets = booklets.values().stream()
				.sorted(Comparator.comparingLong((ExamBooklet b) -> b.getExam().getId())
						.thenComparing(ExamBooklet::getName, String.CASE_INSENSITIVE_ORDER)
						.thenComparingLong(ExamBooklet::getId))
				.toList();
		List<SyllabusVersion> orderedVersions = versions.values().stream()
				.sorted(Comparator.comparing(SyllabusVersion::getName, String.CASE_INSENSITIVE_ORDER)
						.thenComparingLong(SyllabusVersion::getId))
				.toList();
		return new QuestionSearchFilterCatalogue(orderedProviders, List.copyOf(years), orderedExams, orderedBooklets,
				orderedVersions);
	}
}
