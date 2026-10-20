
package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs remaining non-workflow UI tests that are not assigned to the dedicated
 * Search and Dashboard UI suites.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank")
@IncludeClassNamePatterns(".*Test")
@ExcludeClassNamePatterns(".*(QuestionSearchPaneTest|QuestionSearchDialogTest|CorpusDashboardPaneTest|CurriculumSelectorPaneTest|ExamSetupPaneTest)")
@ExcludeTags("workflow-ui")
@IncludeTags("ui")
public class FastUITests {
}
