
package au.edu.eq.questionbank;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs Dashboard, curriculum selector and Exam setup UI tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank.ui")
@IncludeTags("ui")
@IncludeClassNamePatterns(".*(CorpusDashboardPaneTest|CurriculumSelectorPaneTest|ExamSetupPaneTest)")
public class FastUIDashboardTests {
}
