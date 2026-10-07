package au.edu.eq.questionbank.service.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable plan for migrating legacy managed data into the Subject-first
 * application layout.
 *
 * @param moves            safe file/reference moves that can be applied
 * @param archiveOnlyFiles legacy files that are not authoritative persistence
 *                         sources but must be preserved in the recovery archive
 * @param blockers         unresolved conditions preventing migration
 */
public record DataLayoutMigrationPlan(List<Move> moves, List<Path> archiveOnlyFiles, List<Blocker> blockers) {

	/**
	 * Compatibility constructor for plans without archive-only files.
	 *
	 * @param moves    planned moves
	 * @param blockers migration blockers
	 */
	public DataLayoutMigrationPlan(List<Move> moves, List<Blocker> blockers) {
		this(moves, List.of(), blockers);
	}

	/**
	 * Freezes a migration plan.
	 */
	public DataLayoutMigrationPlan {
		Objects.requireNonNull(moves, "moves");
		Objects.requireNonNull(archiveOnlyFiles, "archiveOnlyFiles");
		Objects.requireNonNull(blockers, "blockers");
		moves = List.copyOf(moves);
		archiveOnlyFiles = archiveOnlyFiles.stream()
				.map(path -> Objects.requireNonNull(path, "archiveOnlyFile").toAbsolutePath().normalize()).toList();
		blockers = List.copyOf(blockers);
	}

	/**
	 * Reports whether the complete plan is safe to apply.
	 *
	 * @return true when no blockers remain
	 */
	public boolean canApply() {
		return blockers.isEmpty();
	}

	/**
	 * Reports whether the inspected data root still requires migration work.
	 *
	 * @return true when moves, archive work or blockers remain
	 */
	public boolean migrationRequired() {
		return !moves.isEmpty() || !archiveOnlyFiles.isEmpty() || !blockers.isEmpty();
	}

	/**
	 * Managed asset families participating in migration.
	 */
	public enum AssetKind {
		/**
		 * Question or Answer PDF owned by an Exam.
		 */
		EXAM_PDF,
		/**
		 * PDF containing source material for a syllabus version.
		 */
		CURRICULUM_SOURCE_PDF,
		/**
		 * Retained curriculum workbook assigned explicitly to a Subject/version.
		 */
		CURRICULUM_WORKBOOK
	}

	/**
	 * One safe old-to-new managed asset move.
	 *
	 * @param assetKind                  asset family
	 * @param persistentId               SourceDocument/syllabus id, or zero for a
	 *                                   workbook with no persisted provenance row
	 * @param source                     current legacy source file
	 * @param destination                Subject-first destination
	 * @param oldRelativePath            former legacy-relative representation
	 * @param newRelativePath            data-root-relative destination
	 * @param destinationAlreadyVerified true when an interrupted earlier phase
	 *                                   already produced byte-identical destination
	 *                                   bytes
	 */
	public record Move(AssetKind assetKind, long persistentId, Path source, Path destination, String oldRelativePath,
			String newRelativePath, boolean destinationAlreadyVerified) {

		/**
		 * Validates one planned move.
		 */
		public Move {
			Objects.requireNonNull(assetKind, "assetKind");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(destination, "destination");
			Objects.requireNonNull(oldRelativePath, "oldRelativePath");
			Objects.requireNonNull(newRelativePath, "newRelativePath");
			if (assetKind == AssetKind.CURRICULUM_WORKBOOK) {
				if (persistentId != 0) {
					throw new IllegalArgumentException("Curriculum workbook migration has no persistent id");
				}
			} else if (persistentId < 1) {
				throw new IllegalArgumentException("persistentId must be positive");
			}
			if (oldRelativePath.isBlank()) {
				throw new IllegalArgumentException("oldRelativePath must not be blank");
			}
			if (newRelativePath.isBlank()) {
				throw new IllegalArgumentException("newRelativePath must not be blank");
			}
			source = source.toAbsolutePath().normalize();
			destination = destination.toAbsolutePath().normalize();
		}
	}

	/**
	 * One condition preventing migration from being safely applied.
	 *
	 * @param code    stable diagnostic code
	 * @param message human-readable explanation
	 * @param path    relevant file or directory, or {@code null}
	 */
	public record Blocker(String code, String message, Path path) {

		/**
		 * Validates one blocker.
		 */
		public Blocker {
			if (code == null || code.isBlank()) {
				throw new IllegalArgumentException("code must not be blank");
			}
			if (message == null || message.isBlank()) {
				throw new IllegalArgumentException("message must not be blank");
			}
			if (path != null) {
				path = path.toAbsolutePath().normalize();
			}
		}
	}
}
