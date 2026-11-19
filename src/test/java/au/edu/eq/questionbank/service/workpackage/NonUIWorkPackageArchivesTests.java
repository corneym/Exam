package au.edu.eq.questionbank.service.workpackage;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs archive, codec and package-format regression tests.
 */
@Suite
@SelectClasses({ ContributionArchiveInspectorTest.class, ExamWorkArchiveValidatorTest.class,
		ExamWorkArchiveWriterTest.class, ExamWorkJsonlCodecTest.class, ManifestCodecTest.class,
		WorkAssignmentArchiveFixtureTest.class, WorkAssignmentArchiveStagerTest.class,
		WorkAssignmentAssetExtractorTest.class, WorkAssignmentPublicationJournalTest.class,
		WorkAssignmentScopeValidatorTest.class })
@ExcludeTags("ui")
public class NonUIWorkPackageArchivesTests {
}
