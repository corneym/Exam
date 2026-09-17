package au.edu.eq.questionbank.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A named edition of the syllabus for a {@link Subject}.
 * <p>
 * The current flag identifies the version selected by default for new question
 * classifications. Curriculum status separately records whether the curriculum
 * content has been checked and finalised.
 * <p>
 * Syllabus versions use persistent identifier equality.
 */
public class SyllabusVersion {

	private final long id;
	private final Subject subject;
	private final String name;
	private final boolean current;
	private final CurriculumStatus curriculumStatus;
	private final Instant curriculumFinalisedAt;
	private final String sourcePdfPath;

	/**
	 * Creates an in-progress syllabus version with no managed source PDF.
	 *
	 * @param id      the positive persistent version identifier
	 * @param subject the subject governed by the syllabus
	 * @param name    the non-blank version name, commonly its commencement year
	 * @param current whether this is the subject's current classification version
	 */
	public SyllabusVersion(long id, Subject subject, String name, boolean current) {
		this(id, subject, name, current, CurriculumStatus.IN_PROGRESS, null, null);
	}

	/**
	 * Creates a syllabus version with persisted curriculum-authoring metadata.
	 *
	 * @param id                    persistent version identifier
	 * @param subject               owning subject
	 * @param name                  syllabus version name
	 * @param current               whether this is the current classification
	 *                              version
	 * @param curriculumStatus      authoring lifecycle state
	 * @param curriculumFinalisedAt finalisation instant, or {@code null} while in
	 *                              progress
	 * @param sourcePdfPath         managed relative source-PDF path, or
	 *                              {@code null}
	 */
	public SyllabusVersion(long id, Subject subject, String name, boolean current, CurriculumStatus curriculumStatus,
			Instant curriculumFinalisedAt, String sourcePdfPath) {
		if (id < 1) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (subject == null) {
			throw new NullPointerException("subject");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (curriculumStatus == null) {
			throw new NullPointerException("curriculumStatus");
		}
		if (sourcePdfPath != null && sourcePdfPath.isBlank()) {
			throw new IllegalArgumentException("sourcePdfPath must be null or non-blank");
		}
		if (curriculumStatus == CurriculumStatus.FINAL && curriculumFinalisedAt == null) {
			throw new IllegalArgumentException("FINAL curriculum requires curriculumFinalisedAt");
		}
		if (curriculumStatus == CurriculumStatus.IN_PROGRESS && curriculumFinalisedAt != null) {
			throw new IllegalArgumentException("IN_PROGRESS curriculum must not have curriculumFinalisedAt");
		}
		this.id = id;
		this.subject = subject;
		this.name = name;
		this.current = current;
		this.curriculumStatus = curriculumStatus;
		this.curriculumFinalisedAt = curriculumFinalisedAt;
		this.sourcePdfPath = sourcePdfPath;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof SyllabusVersion other)) {
			return false;
		}
		return id == other.id;
	}

	/**
	 * Returns the recorded time of curriculum finalisation.
	 *
	 * @return finalisation instant, or null while authoring is in progress
	 */
	public Instant getCurriculumFinalisedAt() {
		return curriculumFinalisedAt;
	}

	/**
	 * Returns authoring lifecycle state independently of the current-version flag.
	 *
	 * @return in-progress or final curriculum status
	 */
	public CurriculumStatus getCurriculumStatus() {
		return curriculumStatus;
	}

	/**
	 * Returns the persistent identity of this syllabus edition.
	 *
	 * @return positive syllabus-version identifier
	 */
	public long getId() {
		return id;
	}

	/**
	 * Returns the label distinguishing this syllabus edition within its subject.
	 *
	 * @return non-blank version name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the managed source-PDF reference retained as syllabus provenance.
	 *
	 * @return path relative to the curriculum data root, or null if unattached
	 */
	public String getSourcePdfPath() {
		return sourcePdfPath;
	}

	/**
	 * Returns the school subject governed by this syllabus edition.
	 *
	 * @return owning subject
	 */
	public Subject getSubject() {
		return subject;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	/**
	 * Indicates whether this is the subject's current classification syllabus.
	 *
	 * @return current-version flag, independent of authoring completion
	 */
	public boolean isCurrent() {
		return current;
	}

	/**
	 * Indicates whether the curriculum has been explicitly finalised.
	 *
	 * @return true when its authoring lifecycle state is FINAL
	 */
	public boolean isCurriculumFinal() {
		return curriculumStatus == CurriculumStatus.FINAL;
	}

	@Override
	public String toString() {
		return name + " " + subject.getName();
	}
}
