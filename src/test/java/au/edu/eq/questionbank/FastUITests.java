package au.edu.eq.questionbank;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages("au.edu.eq.questionbank")
@IncludeClassNamePatterns(".*Test")
@ExcludeTags("workflow-ui")
@IncludeTags("ui")
public class FastUITests {
}
