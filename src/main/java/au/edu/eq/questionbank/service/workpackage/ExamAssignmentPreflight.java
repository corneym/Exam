package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.model.AnswerFile;
import au.edu.eq.questionbank.model.Exam;
import au.edu.eq.questionbank.model.ExamAssetExpectations;
import au.edu.eq.questionbank.model.ExamBooklet;
import au.edu.eq.questionbank.model.SourceDocument;
import au.edu.eq.questionbank.model.Subject;
import au.edu.eq.questionbank.model.SyllabusVersion;
import au.edu.eq.questionbank.repository.assessment.SqliteAnswerWriter;
import au.edu.eq.questionbank.repository.assessment.SqliteExamWriter;
import au.edu.eq.questionbank.repository.curriculum.SqliteCurriculumRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Checks whether a master-owned Exam can be issued for offline capture and
 * classification.
 *
 * The check does not create assignments, alter Exam assets, or modify
 * persistent data.
 */
public final class ExamAssignmentPreflight {

	private final SqliteDatabase database;
	private final ManagedDataLayout layout;

	/**
	 * Creates a read-only assignment readiness checker.
	 *
	 * @param database authoritative master database
	 * @param layout   managed source-asset layout
	 */
	public ExamAssignmentPreflight(SqliteDatabase database, ManagedDataLayout layout) {
		this.database = Objects.requireNonNull(database, "database");
		this.layout = Objects.requireNonNull(layout, "layout");
	}

	/**
	 * Verifies that one complete Exam can be issued for offline work, including
	 * opening every required PDF with the application's PDF reader.
	 *
	 * @param requestedExam        Exam selected by the coordinator
	 * @param legacyCaptureEnabled whether the original 2019 syllabus must be
	 *                             available for legacy capture
	 * @return authoritative Exam, asset and syllabus references
	 * @throws SQLException if Exam persistence cannot be read
	 * @throws IOException  if the Exam is not ready for issuance
	 */
	public Result check(Exam requestedExam, boolean legacyCaptureEnabled) throws SQLException, IOException {
		Objects.requireNonNull(requestedExam, "requestedExam");
		SqliteCurriculumRepository curriculum = new SqliteCurriculumRepository(database);
		SqliteExamWriter exams = new SqliteExamWriter(database);
		SqliteAnswerWriter answers = new SqliteAnswerWriter(database, exams);
		Subject subject = curriculum.findSubjectById(requestedExam.getSubject().getId())
				.orElseThrow(() -> new IllegalArgumentException("Assigned Subject does not exist"));
		Exam exam = exams.findExamsForSubject(subject).stream()
				.filter(candidate -> candidate.getId() == requestedExam.getId()).findFirst().orElseThrow(
						() -> new IllegalArgumentException("Assigned Exam does not exist in the selected Subject"));
		List<ExamBooklet> booklets = exams.findExamBooklets(exam);
		List<AnswerFile> answerFiles = answers.findAnswerFiles(exam);
		ExamAssetExpectations expectations = exams.findExamAssetExpectations(exam);
		if (booklets.isEmpty()) {
			throw new IOException("Assignment blocked: Exam has no Question booklets");
		}
		Integer expectedBooklets = expectations.expectedQuestionBookletCount();
		if (expectedBooklets == null || expectedBooklets != booklets.size()) {
			throw new IOException("Assignment blocked: expected Question booklet count is "
					+ "missing or does not match available booklets");
		}
		Integer expectedAnswers = expectations.expectedAnswerFileCount();
		if (expectedAnswers == null || expectedAnswers != answerFiles.size()) {
			throw new IOException(
					"Assignment blocked: expected Answer file count is " + "missing or does not match available files");
		}
		for (ExamBooklet booklet : booklets) {
			requireManagedPdf(subject, booklet.getSourceDocument(), "Question booklet " + booklet.getName());
			if (!answerFiles.isEmpty() && answers.findAnswerFile(booklet) == null) {
				throw new IOException(
						"Assignment blocked: Question booklet " + booklet.getName() + " has no assigned Answer file");
			}
		}
		for (AnswerFile answerFile : answerFiles) {
			requireManagedPdf(subject, answerFile.getSourceDocument(), "Answer file " + answerFile.getName());
		}
		List<SyllabusVersion> versions = curriculum.findVersionsForSubject(subject);
		List<SyllabusVersion> currentVersions = versions.stream().filter(SyllabusVersion::isCurrent).toList();
		if (currentVersions.size() != 1) {
			throw new IOException("Assignment blocked: Subject must have exactly one " + "syllabus marked current");
		}
		SyllabusVersion current = currentVersions.getFirst();
		requireUsableSyllabus(curriculum, current);
		List<SyllabusVersion> historicalVersions = versions.stream()
				.filter(version -> isOriginal2019(version.getName())).toList();
		if (historicalVersions.size() > 1) {
			throw new IOException("Assignment blocked: ambiguous original 2019 syllabus");
		}
		SyllabusVersion historical = historicalVersions.isEmpty() ? null : historicalVersions.getFirst();
		if (legacyCaptureEnabled && historical == null) {
			throw new IOException("Assignment blocked: original 2019 syllabus is required " + "for legacy capture");
		}
		if (historical != null) {
			requireUsableSyllabus(curriculum, historical);
		}
		return new Result(exam, booklets, answerFiles, current, historical);
	}

	private boolean isOriginal2019(String name) {
		return name.equals("2019") || name.endsWith(" 2019");
	}

	private void requireManagedPdf(Subject subject, SourceDocument document, String description) throws IOException {
		Path path;
		try {
			path = layout.resolve(document.getRelativePath());
		} catch (IllegalArgumentException exception) {
			throw new IOException("Assignment blocked: unsafe path for " + description, exception);
		}
		Path subjectRoot = layout.subjectDirectory(subject.getName());

		// Only master-managed, Subject-owned source files may be issued.
		if (!path.startsWith(subjectRoot) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				|| !Files.isReadable(path)) {
			throw new IOException(
					"Assignment blocked: missing or unreadable " + description + ": " + document.getRelativePath());
		}

		// Physical containment protects against paths redirected through
		// symbolic-link parent directories.
		if (!path.toRealPath().startsWith(subjectRoot.toRealPath())) {
			throw new IOException("Assignment blocked: source file escapes Subject storage: " + description);
		}
		if (Files.size(path) == 0) {
			throw new IOException("Assignment blocked: empty source file: " + description);
		}
		WorkAssignmentPdfValidator.validate(path, description);
		String expectedHash = document.getContentSha256();
		if (expectedHash == null) {
			return;
		}
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				digest.update(buffer, 0, count);
			}
		}
		String actualHash = HexFormat.of().formatHex(digest.digest());
		if (!expectedHash.equals(actualHash)) {
			throw new IOException("Assignment blocked: source PDF hash mismatch: " + description);
		}
	}

	private void requireUsableSyllabus(SqliteCurriculumRepository curriculum, SyllabusVersion version)
			throws IOException {

		// Questions can only be classified against Subtopics or Descriptors.
		// A hierarchy containing only Units and Topics is insufficient.
		if (curriculum.findReviewNodes(version).isEmpty()) {
			throw new IOException("Assignment blocked: syllabus has no usable Subtopic or Descriptor "
					+ "for Question classification: " + version.getName());
		}
		String sourcePdfPath = version.getSourcePdfPath();
		if (sourcePdfPath != null) {
			Path pdf;
			try {
				pdf = layout.resolve(sourcePdfPath);
			} catch (IllegalArgumentException exception) {
				throw new IOException("Assignment blocked: unsafe syllabus PDF path", exception);
			}
			if (!pdf.startsWith(layout.subjectDirectory(version.getSubject().getName()))
					|| !Files.isRegularFile(pdf, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(pdf)) {
				throw new IOException("Assignment blocked: missing syllabus PDF for " + version.getName());
			}
			Path subjectRoot = layout.subjectDirectory(version.getSubject().getName());
			if (!pdf.toRealPath().startsWith(subjectRoot.toRealPath())) {
				throw new IOException("Assignment blocked: syllabus PDF escapes Subject storage");
			}
			WorkAssignmentPdfValidator.validate(pdf, "syllabus " + version.getName());
		}
	}

	/**
	 * Read-only references to an Exam's verified issuance prerequisites.
	 *
	 * @param exam               authoritative master Exam
	 * @param booklets           complete Question booklet collection
	 * @param answerFiles        complete Answer file collection
	 * @param currentSyllabus    explicitly current syllabus
	 * @param legacy2019Syllabus historical syllabus, if present
	 */
	public record Result(Exam exam, List<ExamBooklet> booklets, List<AnswerFile> answerFiles,
			SyllabusVersion currentSyllabus, SyllabusVersion legacy2019Syllabus) {

		/**
		 * Validates and defensively copies the issuance-readiness result.
		 */
		public Result {
			Objects.requireNonNull(exam, "exam");
			booklets = List.copyOf(booklets);
			answerFiles = List.copyOf(answerFiles);
			Objects.requireNonNull(currentSyllabus, "currentSyllabus");
		}
	}
}
