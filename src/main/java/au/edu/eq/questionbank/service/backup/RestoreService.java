package au.edu.eq.questionbank.service.backup;

import java.nio.file.Path;

/**
 * Application-level boundary for validating and preparing backup restores.
 */
public interface RestoreService {

	/**
	 * Validates and stages a backup without modifying current application data.
	 *
	 * @param backupPath backup archive to prepare
	 * @return prepared restore that must be closed if it is not subsequently
	 *         applied
	 * @throws RestoreException     if validation or staging fails
	 * @throws NullPointerException if {@code backupPath} is {@code null}
	 */
	RestorePreparation prepareRestore(Path backupPath) throws RestoreException;
}
