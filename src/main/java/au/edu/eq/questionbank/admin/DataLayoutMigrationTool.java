package au.edu.eq.questionbank.admin;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import au.edu.eq.questionbank.ApplicationConfig;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.migration.CurriculumWorkbookMigrationAssignment;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationExecutor;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationFinalizationResult;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationFinalizer;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlan;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationPlanner;
import au.edu.eq.questionbank.service.migration.DataLayoutMigrationResult;

/**
 * Command-line administration entry point for Subject-first data migration.
 * <p>
 * Existing installations should normally be migrated with {@code --config} so
 * any legacy explicit PDF, curriculum and database paths are read from the same
 * authoritative configuration used by the desktop application.
 */
public final class DataLayoutMigrationTool {

	private DataLayoutMigrationTool() {
	}

	/**
	 * Runs the administration tool.
	 *
	 * @param args command-line arguments
	 */
	public static void main(String[] args) {
		int result = run(args, System.out, System.err);
		if (result != 0) {
			System.exit(result);
		}
	}

	/**
	 * Runs dry-run or apply mode.
	 *
	 * @param args   migration arguments
	 * @param output normal report destination
	 * @param error  error destination
	 * @return zero for success, one for blockers/failure, two for invalid arguments
	 */
	public static int run(String[] args, PrintStream output, PrintStream error) {
		if (args == null) {
			throw new NullPointerException("args");
		}
		if (output == null) {
			throw new NullPointerException("output");
		}
		if (error == null) {
			throw new NullPointerException("error");
		}
		Arguments arguments = parseArguments(args);
		if (arguments == null) {
			printUsage(error);
			return 2;
		}
		try {
			ApplicationConfig config = loadConfiguration(arguments);
			SqliteDatabase database = new SqliteDatabase(config.databasePath());
			DataLayoutMigrationPlan plan = new DataLayoutMigrationPlanner(config, database,
					arguments.workbookAssignments()).plan();
			printPlan(config, plan, output);
			if (!plan.migrationRequired()) {

				// A successful apply against an already-current installation is also
				// the point at which an old explicit-path configuration can be reduced
				// to the final data.root contract.
				if (arguments.apply()) {
					normaliseConfiguration(arguments, config, output);
				}
				return 0;
			}
			if (!plan.canApply()) {
				output.println();
				output.println("Migration cannot be applied until every blocker is resolved.");
				return 1;
			}
			if (!arguments.apply()) {
				output.println();
				output.println("Dry-run only. No files, database references or configuration were changed.");
				return 0;
			}
			DataLayoutMigrationResult migration = new DataLayoutMigrationExecutor(config, database).apply(plan);
			DataLayoutMigrationFinalizationResult finalization = new DataLayoutMigrationFinalizer(config, database)
					.finalizeMigration();

			// A successful admin run must leave the same planner used by startup with
			// no residual old-layout work.
			DataLayoutMigrationPlan verification = new DataLayoutMigrationPlanner(config, database).plan();
			if (verification.migrationRequired()) {
				throw new IllegalStateException(
						"Migration completed its apply phase but final verification still reports migration work");
			}
			normaliseConfiguration(arguments, config, output);
			output.println();
			output.println("Subject-first migration completed successfully.");
			output.println("Copied managed files: " + migration.copiedFiles());
			output.println("Reused managed files: " + migration.reusedFiles());
			output.println("Updated Exam source paths: " + migration.updatedExamPaths());
			output.println("Updated curriculum source paths: " + migration.updatedCurriculumSourcePaths());
			output.println("Archived legacy files: " + finalization.archivedFiles());
			output.println("Reused recovery-archive files: " + finalization.reusedArchiveFiles());
			output.println("Legacy managed roots are no longer active.");
			return 0;
		} catch (Exception exception) {
			error.println(
					"Migration " + (arguments.apply() ? "apply" : "inspection") + " failed: " + exception.getMessage());
			return 1;
		}
	}

	private static ApplicationConfig loadConfiguration(Arguments arguments) throws Exception {
		if (arguments.configurationFile() != null) {
			return ApplicationConfig.load(arguments.configurationFile());
		}
		return ApplicationConfig.fromDataRoot(arguments.dataRoot());
	}

	private static void normaliseConfiguration(Arguments arguments, ApplicationConfig config, PrintStream output)
			throws Exception {
		if (arguments.configurationFile() == null) {
			return;
		}

		// Once old managed roots have ceased to be active, retain only the final
		// application data-root contract. This prevents obsolete external root
		// locations from surviving indefinitely in configuration.
		ApplicationConfig.saveDataRoot(arguments.configurationFile(), config.dataRoot());
		output.println();
		output.println("Configuration normalised to data root: " + config.dataRoot());
	}

	private static Arguments parseArguments(String[] args) {
		Path dataRoot = null;
		Path configurationFile = null;
		Boolean apply = null;
		List<CurriculumWorkbookMigrationAssignment> assignments = new ArrayList<>();
		for (int index = 0; index < args.length; index++) {
			switch (args[index]) {
			case "--config" -> {
				if (configurationFile != null || index + 1 >= args.length) {
					return null;
				}
				configurationFile = Path.of(args[++index]).toAbsolutePath().normalize();
			}
			case "--data-root" -> {
				if (dataRoot != null || index + 1 >= args.length) {
					return null;
				}
				dataRoot = Path.of(args[++index]).toAbsolutePath().normalize();
			}
			case "--assign-workbook" -> {
				if (index + 3 >= args.length) {
					return null;
				}
				assignments.add(new CurriculumWorkbookMigrationAssignment(args[++index], args[++index], args[++index]));
			}
			case "--dry-run" -> {
				if (apply != null) {
					return null;
				}
				apply = Boolean.FALSE;
			}
			case "--apply" -> {
				if (apply != null) {
					return null;
				}
				apply = Boolean.TRUE;
			}
			default -> {
				return null;
			}
			}
		}

		// Exactly one configuration source is required. --config is preferred for a
		// real installation because it preserves explicit legacy roots.
		if ((dataRoot == null) == (configurationFile == null)) {
			return null;
		}
		if (apply == null) {
			return null;
		}
		return new Arguments(dataRoot, configurationFile, apply.booleanValue(), assignments);
	}

	private static void printPlan(ApplicationConfig config, DataLayoutMigrationPlan plan, PrintStream output) {
		output.println("Subject-first data migration plan");
		output.println("Data root: " + config.dataRoot());
		output.println("Legacy PDF root: " + config.pdfDataRoot());
		output.println("Legacy curriculum root: " + config.curriculumDataRoot());
		output.println("Database: " + config.databasePath());
		output.println();
		if (!plan.migrationRequired()) {
			output.println("Migration required: no");
			return;
		}
		output.println("Migration required: yes");
		output.println("Planned managed moves: " + plan.moves().size());
		output.println("Archive-only legacy files: " + plan.archiveOnlyFiles().size());
		output.println("Blockers: " + plan.blockers().size());
		output.println();
		for (DataLayoutMigrationPlan.Move move : plan.moves()) {
			if (move.assetKind() == DataLayoutMigrationPlan.AssetKind.CURRICULUM_WORKBOOK) {
				output.println("MOVE " + move.assetKind());
			} else {
				output.println("MOVE " + move.assetKind() + " " + move.persistentId());
			}
			output.println("  from: " + move.source());
			output.println("  to:   " + move.destination());
			output.println("  path: " + move.oldRelativePath() + " -> " + move.newRelativePath());
			if (move.destinationAlreadyVerified()) {
				output.println("  destination already contains verified identical bytes");
			}
		}
		for (Path path : plan.archiveOnlyFiles()) {
			output.println("ARCHIVE-ONLY " + path);
		}
		for (DataLayoutMigrationPlan.Blocker blocker : plan.blockers()) {
			output.println("BLOCKER " + blocker.code() + ": " + blocker.message());
			if (blocker.path() != null) {
				output.println("  path: " + blocker.path());
			}
		}
	}

	private static void printUsage(PrintStream error) {
		error.println(
				"""
						Usage:
						  DataLayoutMigrationTool --config <questionbank.properties> [--assign-workbook <legacy-relative.xlsx> <Subject> <Version>]... --dry-run
						  DataLayoutMigrationTool --config <questionbank.properties> [--assign-workbook <legacy-relative.xlsx> <Subject> <Version>]... --apply

						  DataLayoutMigrationTool --data-root <directory> [--assign-workbook <legacy-relative.xlsx> <Subject> <Version>]... --dry-run
						  DataLayoutMigrationTool --data-root <directory> [--assign-workbook <legacy-relative.xlsx> <Subject> <Version>]... --apply

						Use --config for an existing installation. --data-root is intended for
						already-consolidated or disposable data roots.
						""");
	}

	private record Arguments(Path dataRoot, Path configurationFile, boolean apply,
			List<CurriculumWorkbookMigrationAssignment> workbookAssignments) {

		private Arguments {
			workbookAssignments = List.copyOf(workbookAssignments);
		}
	}
}
