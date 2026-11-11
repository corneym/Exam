package au.edu.eq.questionbank.service.workpackage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable metadata envelope for a version-one .eqwork package.
 *
 * <p>
 * All transferable identities are UUIDs. Database-local integer identifiers and
 * machine-specific paths are excluded from the contract.
 *
 * @param formatVersion                   package contract version
 * @param packageId                       immutable identity of this package
 * @param packageType                     purpose of the package
 * @param createdAt                       package creation instant
 * @param originInstallationId            originating installation identity
 * @param intendedMasterInstallationId    intended coordinator, or null
 * @param subjectId                       portable Subject identity
 * @param subjectName                     human-readable Subject name
 * @param assignmentId                    assignment identity, where applicable
 * @param baselineSnapshotId              baseline identity, where applicable
 * @param producerApplicationVersion      exporting application version
 * @param minimumReaderApplicationVersion minimum compatible application
 * @param producerSchemaVersion           source database schema version
 * @param minimumReaderSchemaVersion      minimum supported reader schema
 * @param scope                           Subject, Exam or Booklet transfer
 *                                        scope
 * @param entries                         indexed archive content entries
 */
public record EqworkManifest(int formatVersion, UUID packageId, EqworkPackageType packageType, Instant createdAt,
		UUID originInstallationId, UUID intendedMasterInstallationId, UUID subjectId, String subjectName,
		UUID assignmentId, UUID baselineSnapshotId, String producerApplicationVersion,
		String minimumReaderApplicationVersion, int producerSchemaVersion, int minimumReaderSchemaVersion, Scope scope,
		List<Entry> entries) {

	public static final int CURRENT_FORMAT_VERSION = 1;
	private static final Pattern APPLICATION_VERSION = Pattern
			.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+][A-Za-z0-9.-]+)?$");

	/**
	 * Validates and normalises the immutable manifest values.
	 */
	public EqworkManifest {
		if (formatVersion != CURRENT_FORMAT_VERSION) {
			throw new IllegalArgumentException("Unsupported .eqwork format version: " + formatVersion);
		}
		Objects.requireNonNull(packageId, "packageId");
		Objects.requireNonNull(packageType, "packageType");
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(scope, "scope");
		requireText(subjectName, "subjectName");
		requireText(producerApplicationVersion, "producerApplicationVersion");
		requireText(minimumReaderApplicationVersion, "minimumReaderApplicationVersion");
		if (compareApplicationVersions(producerApplicationVersion, minimumReaderApplicationVersion) < 0) {
			throw new IllegalArgumentException("Minimum reader application version exceeds producer version");
		}
		if (producerSchemaVersion < 1 || minimumReaderSchemaVersion < 1
				|| minimumReaderSchemaVersion > producerSchemaVersion) {
			throw new IllegalArgumentException("Invalid database schema compatibility requirements");
		}
		if (packageType == EqworkPackageType.SUBJECT_SNAPSHOT && scope.level() != ScopeLevel.SUBJECT) {
			throw new IllegalArgumentException("A Subject snapshot must have Subject scope");
		}
		if (packageType == EqworkPackageType.WORK_ASSIGNMENT || packageType == EqworkPackageType.CONTRIBUTION) {
			if (intendedMasterInstallationId == null || assignmentId == null || baselineSnapshotId == null) {
				throw new IllegalArgumentException(
						"Assignments and contributions require master, assignment and baseline identities");
			}
		}
		if (packageType == EqworkPackageType.IMPORT_RECEIPT && intendedMasterInstallationId == null) {
			throw new IllegalArgumentException("Import receipts require a master identity");
		}
		List<Entry> sortedEntries = new ArrayList<>(List.copyOf(Objects.requireNonNull(entries, "entries")));
		sortedEntries.sort(Comparator.comparing(Entry::path));
		Set<String> portablePaths = new HashSet<>();
		for (Entry entry : sortedEntries) {

			// ZIP entry names are case-sensitive, but destination file systems
			// may not be. Do not allow two names to identify the same file.
			String portableKey = entry.path().toLowerCase(Locale.ROOT);
			if (!portablePaths.add(portableKey)) {
				throw new IllegalArgumentException("Duplicate or case-colliding indexed archive path: " + entry.path());
			}
		}
		entries = List.copyOf(sortedEntries);
	}

	/**
	 * Checks the declared minimum reader versions.
	 *
	 * <p>
	 * The producer versions describe provenance. Compatibility is governed by the
	 * explicitly declared minimum reader versions, not by an implicit requirement
	 * for an identical producer schema.
	 *
	 * @param applicationVersion installed application version
	 * @param schemaVersion      installed database schema version
	 * @throws EqworkFormatException if the reader is incompatible
	 */
	public void requireCompatibleReader(String applicationVersion, int schemaVersion) throws EqworkFormatException {
		if (schemaVersion < minimumReaderSchemaVersion) {
			throw new EqworkFormatException("Incompatible .eqwork database schema version");
		}
		try {
			if (compareApplicationVersions(applicationVersion, minimumReaderApplicationVersion) < 0) {
				throw new EqworkFormatException("Incompatible .eqwork application version");
			}
		} catch (IllegalArgumentException exception) {
			throw new EqworkFormatException("Unrecognised application version", exception);
		}
	}

	private static int compareApplicationVersions(String first, String second) {
		int[] left = versionComponents(first);
		int[] right = versionComponents(second);
		for (int index = 0; index < left.length; index++) {
			int comparison = Integer.compare(left[index], right[index]);
			if (comparison != 0) {
				return comparison;
			}
		}
		return 0;
	}

	private static List<UUID> normaliseIds(List<UUID> identities) {
		List<UUID> result = new ArrayList<>(List.copyOf(Objects.requireNonNull(identities, "identities")));
		result.sort(Comparator.comparing(UUID::toString));
		if (new HashSet<>(result).size() != result.size()) {
			throw new IllegalArgumentException("Duplicate portable identity in scope");
		}
		return List.copyOf(result);
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank() || value.length() > 256) {
			throw new IllegalArgumentException(name + " must contain 1 to 256 characters");
		}
	}

	private static int[] versionComponents(String version) {
		Matcher matcher = APPLICATION_VERSION.matcher(Objects.requireNonNull(version, "version"));
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid application version: " + version);
		}
		return new int[] { Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
				Integer.parseInt(matcher.group(3)) };
	}

	/**
	 * Identifies the granularity of the exported records.
	 */
	public enum ScopeLevel {
		/** The entire Subject. */
		SUBJECT,
		/** One or more specified Exams. */
		EXAMS,
		/** One or more specified Exam Booklets. */
		BOOKLETS
	}

	/**
	 * Defines a portable package scope.
	 *
	 * @param level      scope granularity
	 * @param examIds    portable Exam identities
	 * @param bookletIds portable Booklet identities
	 */
	public record Scope(ScopeLevel level, List<UUID> examIds, List<UUID> bookletIds) {

		/**
		 * Normalises identity ordering and validates scope membership.
		 */
		public Scope {
			Objects.requireNonNull(level, "level");
			examIds = normaliseIds(examIds);
			bookletIds = normaliseIds(bookletIds);
			if (level == ScopeLevel.SUBJECT && (!examIds.isEmpty() || !bookletIds.isEmpty())) {
				throw new IllegalArgumentException("Subject scope cannot contain Exam or Booklet filters");
			}
			if (level == ScopeLevel.EXAMS && (examIds.isEmpty() || !bookletIds.isEmpty())) {
				throw new IllegalArgumentException("Exam scope requires Exam identities only");
			}
			if (level == ScopeLevel.BOOKLETS && bookletIds.isEmpty()) {
				throw new IllegalArgumentException("Booklet scope requires Booklet identities");
			}
		}
	}

	/**
	 * Identifies the responsibility of an indexed archive entry.
	 */
	public enum EntryRole {
		/** Structured JSONL domain records. */
		RECORDS,
		/** Immutable binary assets addressed by SHA-256. */
		ASSET,
		/** Immutable baseline data for contribution reconciliation. */
		BASELINE,
		/** Structured indexes describing package content. */
		INDEX,
		/** Import acknowledgement and reconciliation outcomes. */
		RECEIPT
	}

	/**
	 * Describes one immutable archive entry.
	 *
	 * @param path   portable archive-relative path
	 * @param role   entry responsibility
	 * @param size   uncompressed byte length
	 * @param sha256 SHA-256 digest of the uncompressed bytes
	 */
	public record Entry(String path, EntryRole role, long size, String sha256) {

		/**
		 * Validates the indexed entry's portable path and content metadata.
		 */
		public Entry {
			Objects.requireNonNull(role, "role");
			if (path == null || path.isBlank() || path.length() > 256 || path.startsWith("/") || path.endsWith("/")
					|| path.contains("\\") || path.contains(":")) {
				throw new IllegalArgumentException("Unsafe archive entry path: " + path);
			}
			boolean allowedRoot = path.startsWith("records/") || path.startsWith("assets/")
					|| path.startsWith("baseline/") || path.startsWith("receipts/");
			if (!allowedRoot) {
				throw new IllegalArgumentException("Unsupported archive entry path: " + path);
			}
			for (String component : path.split("/", -1)) {
				if (component.equals(".") || component.equals("..") || component.endsWith(".")
						|| !component.matches("[A-Za-z0-9._-]+")
						|| component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
					throw new IllegalArgumentException("Unsafe archive entry path: " + path);
				}
			}
			if (size < 0) {
				throw new IllegalArgumentException("Archive entry size must not be negative");
			}
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid SHA-256 archive entry digest");
			}
		}
	}
}
