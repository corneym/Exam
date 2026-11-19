package au.edu.eq.questionbank.service.workpackage;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs subject import, publication and consolidation tests.
 */
@Suite
@SelectClasses({ PortableSemanticImportIntegrationTest.class, SubjectImportApprovalPlanTest.class,
		SubjectImportAssetStagerTest.class, SubjectImportBackupGateTest.class,
		SubjectImportConflictResolutionTest.class, SubjectImportCoordinatorIntegrationTest.class,
		SubjectImportCurriculumMappingsTest.class, SubjectImportGraphWriterAssignmentTest.class,
		SubjectImportPublicationJournalTest.class, SubjectImportSnapshotFingerprintTest.class,
		SubjectReconciliationReviewTest.class, SubjectSnapshotExporterTest.class })
@ExcludeTags("ui")
public class NonUIWorkPackageSubjectImportTests {
}
