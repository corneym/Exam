package au.edu.eq.questionbank.service.backup;

/**
 * Applies a previously validated and staged restore.
 */
public interface RestoreExecutor {

	/**
	 * Applies a prepared restore after first creating a full safety backup of the
	 * current data.
	 *
	 * @param preparation validated staged restore
	 * @param resources   active application resources that must be closed before
	 *                    replacement
	 * @return successful restore result
	 * @throws RestoreException if the restore cannot be safely completed
	 */
	RestoreResult applyRestore(RestorePreparation preparation, AutoCloseable resources) throws RestoreException;
}
