package au.edu.eq.questionbank.service.workpackage;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs Subject snapshot export, import, refresh, publication, authority
 * validation and consolidation tests.
 */
@Suite
@SelectClasses({ PortableSemanticImportIntegrationTest.class, SubjectImportApprovalPlanTest.class,
		SubjectImportAssetStagerTest.class, SubjectImportBackupGateTest.class,
		SubjectImportConflictResolutionTest.class, SubjectImportCoordinatorIntegrationTest.class,
		SubjectImportCurriculumMappingsTest.class, SubjectImportGraphWriterAssignmentTest.class,
		SubjectImportPublicationJournalTest.class, SubjectImportSnapshotFingerprintTest.class,
		SubjectReconciliationReviewTest.class, SubjectRefreshChangeClassifierTest.class,
		SubjectRefreshIdentityNormaliserTest.class, SubjectRefreshPlanTest.class, SubjectSnapshotExporterTest.class,
		SubjectSnapshotFreshnessTest.class, SubjectSnapshotIntakePreviewTest.class })
@ExcludeTags("ui")
public class NonUIWorkPackageSubjectImportTests {
}
