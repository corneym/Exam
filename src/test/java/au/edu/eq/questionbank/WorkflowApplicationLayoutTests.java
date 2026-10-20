
package au.edu.eq.questionbank;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs application capture workspace layout tests in a dedicated JVM.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank.ui")
@IncludeTags("workflow-ui")
@IncludeClassNamePatterns(".*CaptureWorkspaceLayoutTest")
public class WorkflowApplicationLayoutTests {
}
