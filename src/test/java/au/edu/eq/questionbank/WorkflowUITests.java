package au.edu.eq.questionbank;

import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages("au.edu.eq.questionbank.ui")
@IncludeClassNamePatterns(".*Test")
@IncludeTags("workflow-ui")
public class WorkflowUITests {
}
