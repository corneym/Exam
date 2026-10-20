
package au.edu.eq.questionbank;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Runs selection and Question editing workflow tests.
 */
@Suite
@SelectPackages("au.edu.eq.questionbank.ui")
@IncludeTags("workflow-ui")
@IncludeClassNamePatterns(".*(CaptureSelectionWorkflowTest|QuestionEditingWorkflowTest)")
public class WorkflowStateEditingSelectionTests {
}
