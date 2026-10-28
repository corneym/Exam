package au.edu.eq.questionbank.output.revision;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, portable identity shared by every artifact produced during one
 * revision export.
 * <p>
 * The display form is intended for people selecting exports, while the
 * technical token is safe to embed in package identifiers. Both forms are
 * derived only from the generation time and contain no filesystem or machine
 * information.
 */
public final class RevisionExportIdentity {

	private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter
			.ofPattern("dd MMM uuuu", Locale.ENGLISH);
	private static final DateTimeFormatter TECHNICAL_FORMAT = DateTimeFormatter
			.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	private final ZonedDateTime generatedAt;

	/**
	 * Creates an identity for an export generated at the supplied time.
	 *
	 * @param generatedAt generation time, including the display time zone
	 * @throws NullPointerException if {@code generatedAt} is null
	 */
	public RevisionExportIdentity(ZonedDateTime generatedAt) {
		Objects.requireNonNull(generatedAt, "generatedAt");
		this.generatedAt = generatedAt.truncatedTo(ChronoUnit.MILLIS);
	}

	/**
	 * Returns the human-readable identity suffix.
	 *
	 * @return generation date for human-readable presentation
	 */
	public String getDisplayText() {
		return DISPLAY_FORMAT.format(generatedAt);
	}

	/**
	 * Returns the generation time represented by this identity.
	 *
	 * @return generation time truncated to milliseconds
	 */
	public ZonedDateTime getGeneratedAt() {
		return generatedAt;
	}

	/**
	 * Returns a portable token suitable for a SCORM manifest identifier.
	 *
	 * @return UTC generation timestamp containing only identifier-safe characters
	 */
	public String getTechnicalToken() {
		return TECHNICAL_FORMAT.format(generatedAt.toInstant());
	}

	/**
	 * Builds the subject-based revision title for this export.
	 *
	 * @param subjectName exported Subject name
	 * @return Subject Exam Question Corpus title including the export date
	 * @throws NullPointerException     if {@code subjectName} is null
	 * @throws IllegalArgumentException if {@code subjectName} is blank
	 */
	public String titleFor(String subjectName) {
		Objects.requireNonNull(subjectName, "subjectName");
		if (subjectName.isBlank()) {
			throw new IllegalArgumentException("subjectName must not be blank");
		}
		return subjectName + " Exam Question Corpus (" + getDisplayText() + ")";
	}
}
