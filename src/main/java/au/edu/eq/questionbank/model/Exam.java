package au.edu.eq.questionbank.model;

/**
 * An assessment event or paper issued for a subject in a particular year.
 * <p>
 * An exam is the logical assessment, not an individual PDF file. One exam may
 * have multiple {@link ExamBooklet booklets}, each backed by its own
 * {@link SourceDocument}.
 */
public class Exam {

	private final long id;
	private final Subject subject;
	private final ExamProvider provider;
	private final int year;
	private final String name;

	/**
	 * Creates an exam and associates it with its subject and issuing provider.
	 *
	 * @param id       the persistent exam identifier
	 * @param subject  the science subject being assessed
	 * @param provider the organisation that issued the assessment
	 * @param year     the calendar year of the assessment
	 * @param name     the human-readable assessment name
	 * @throws NullPointerException     if {@code subject} or {@code provider} is
	 *                                  {@code null}
	 * @throws IllegalArgumentException if {@code id} or {@code year} is not
	 *                                  positive, or {@code name} is null or blank
	 */
	public Exam(long id, Subject subject, ExamProvider provider, int year, String name) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (provider == null) {
			throw new NullPointerException("provider");
		}
		if (year < 1) {
			throw new IllegalArgumentException("year must be positive");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		this.id = id;
		this.subject = subject;
		this.provider = provider;
		this.year = year;
		this.name = name;
	}

	/**
	 * Returns the persistent identity of this examination.
	 *
	 * @return positive exam identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the assessment name used to distinguish examinations.
	 *
	 * @return non-blank assessment name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the organisation responsible for this examination.
	 *
	 * @return examination provider
	 */
	public ExamProvider getProvider() {
		return provider;
	}

	/**
	 * Returns the subject assessed by this examination.
	 *
	 * @return assessed subject
	 */
	public Subject getSubject() {
		return subject;
	}

	/**
	 * Returns the year in which this examination was held.
	 *
	 * @return positive examination year
	 */
	public int getYear() {
		return year;
	}
}
