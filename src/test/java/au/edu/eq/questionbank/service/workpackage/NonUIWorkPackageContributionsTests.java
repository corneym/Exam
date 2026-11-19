package au.edu.eq.questionbank.service.workpackage;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs contribution, reconciliation and return-ledger tests.
 */
@Suite
@SelectClasses({ AssessmentReconcilerTest.class, CaptureReconcilerTest.class, ContributionDeltaPlannerTest.class,
		ContributionSnapshotReaderTest.class, CurriculumReconcilerTest.class, ImageReconcilerTest.class,
		SqliteContributionReturnLedgerTest.class, SubjectReconciliationPreviewServiceTest.class })
@ExcludeTags("ui")
public class NonUIWorkPackageContributionsTests {
}
