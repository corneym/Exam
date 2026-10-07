package au.edu.eq.questionbank.service.migration;

/**
 * Raised when normal application startup detects managed data that still
 * requires Subject-first migration.
 */
public final class DataLayoutMigrationRequiredException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a migration-required failure.
	 *
	 * @param plan detected migration work
	 */
	public DataLayoutMigrationRequiredException(DataLayoutMigrationPlan plan) {
		super(message(plan));
	}

	private static String message(DataLayoutMigrationPlan plan) {
		if (plan == null) {
			throw new NullPointerException("plan");
		}
		return "Subject-first migration is required: " + plan.moves().size() + " planned move(s), "
				+ plan.archiveOnlyFiles().size() + " archive-only file(s), " + plan.blockers().size() + " blocker(s).";
	}
}
