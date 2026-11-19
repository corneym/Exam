package au.edu.eq.questionbank.service.workpackage;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs assignment lifecycle and contributor installation tests.
 */
@Suite
@SelectClasses({ ExamAssignmentPreflightTest.class, SqliteAssignmentMutationGuardTest.class,
		SqliteContributorAssignmentInstallationRecordTest.class, SqliteContributorAssignmentSessionRepositoryTest.class,
		WorkAssignmentInstallationCoordinatorTest.class, WorkAssignmentManagedAssetGateTest.class })
@ExcludeTags("ui")
public class NonUIWorkPackageAssignmentsTests {
}
