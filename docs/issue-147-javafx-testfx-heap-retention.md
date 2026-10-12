# Issue #147 — JavaFX/TestFX heap retention investigation and resolution

**Project:** Exam Question Bank  
**Issue:** https://github.com/corneym/Exam/issues/147  
**Investigation date:** 12 October 2026  
**Suggested repository location:** `docs/issue-147-javafx-testfx-heap-retention.md`

## Problem and acceptance criteria

Launching `au.edu.eq.questionbank.AllTests` directly from Eclipse as a single JUnit test run previously resulted in `java.lang.OutOfMemoryError: Java heap space` despite a 4 GiB maximum Java heap. Splitting UI suites into separate Maven/Surefire JVMs did not meet the requirement to preserve Eclipse's single progress bar, individual JUnit results and clickable failures.

The objective was to identify the cause of accumulated JavaFX/TestFX state, correct its lifecycle, add deterministic regression coverage and confirm the complete JUnit run at `-Xmx4g`.

## Initial failure and diagnosis

The original failed JUnit run produced a roughly 4.96 GB heap dump (`java_pid60276.hprof`). The failure occurred during PDF page rendering while preparing `QuestionEditingWorkflowTest.recaptureQuestionStartsEmptyWithoutChangingStoredRegions()`.

Eclipse Memory Analyzer (MAT) identified:

- **207** instances of `QuestionBankApplication`.
- **207** instances of `PdfWorkspacePane`.
- **207** instances of `javafx.scene.Scene`.
- Large image/pixel-array retention associated with JavaFX Prism rendering, including the software texture cache.

The likely retention path was an anonymous listener registered on the TestFX-reused primary `Stage` in `QuestionBankApplication.configurePrimaryStage()`. That listener captured application state and was not removed in `QuestionBankApplication.stop()`. Because the primary Stage can survive across individual TestFX test fixtures, its listener registration could preserve previous application graphs, including PDF workspaces and image references.

These MAT findings identified a strong lifecycle hypothesis; the raw object histogram by itself did not prove which individual reference held every retained object.

## Implemented correction

### Batch 01 — Stage focus-listener lifecycle

- Track the listener installed on the primary Stage rather than installing an unremovable anonymous listener.
- Detach that listener during application shutdown, using a `finally` block so exception/early-return paths do not skip cleanup.
- Perform the UI test fixture's application shutdown on the JavaFX Application Thread.
- Preserve the existing clipboard-availability refresh when the application regains focus.

### Batch 02 — Deterministic regression coverage

- Extract focus registration/removal into package-private `FocusRefreshRegistration`, with idempotent `close()`.
- Exercise repeated listener register/close cycles in `FocusRefreshRegistrationTest`, asserting previously closed callbacks remain inactive and a new callback responds correctly.
- Exercise repeated real-application start/stop cycles using the reused TestFX Stage in `QuestionBankApplicationFocusLifecycleTest`, checking each application discards its focus registration.

These tests check deterministic lifecycle behaviour rather than using nondeterministic `System.gc()`-based assertions. The full-application lifecycle test and the focused listener test were reported **green** under the established headless Maven configuration on 12 October 2026.

The fix addresses Stage-listener retention. It does **not** establish that all JavaFX Prism texture caches are released immediately after a test, nor does it claim changes to the Prism renderer itself.

## Test and verification commands

Targeted regression tests (PowerShell, repository root):

```powershell
.\mvnw.cmd -Pheadless-ui-tests "-Dtest=FocusRefreshRegistrationTest,QuestionBankApplicationFocusLifecycleTest" test
```

Previously verified targeted workflow:

```powershell
.\mvnw.cmd -Pheadless-ui-tests "-Dtest=QuestionEditingWorkflowTest" test
```

Full suite: launch `AllTests.java` directly through **Eclipse → Run As → JUnit Test** with the usual JavaFX/TestFX headless module options and:

```text
-Xmx4g
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=target
-Xlog:gc*:file=target/alltests-gc.log:time,uptime,level,tags
```

The full single-JVM Eclipse JUnit suite **passed all tests in 527 seconds (8 min 47 sec)** after Batch 01. It completed without an `OutOfMemoryError`, retaining the native JUnit test hierarchy and progress bar. This result was recorded before Batch 02; the Batch 02 focused tests were subsequently reported green. A further full-suite rerun was not required for the focused listener abstraction.

## GC-log evidence from the passing full run

- **JDK:** 25.0.4; **collector:** G1; **maximum heap:** 4,096 MiB.
- **No full-GC pause or heap exhaustion** recorded in the successful run.
- **89** recorded young/mixed evacuation pauses (plus additional concurrent-mark phases).
- Highest observed pre-collection used heap: approximately **2,903 MiB**, followed by **1,265 MiB** after that collection.
- Later post-collection measurements clustered around **1.2–1.3 GiB**, although these alone do not prove a steady-state plateau.
- The final JVM exit heap usage was not a post-GC measurement and must not be interpreted as permanently retained memory.

GC logging establishes the memory trajectory and absence of exhaustion during that run; it cannot identify individual Java objects or the cause of their retention.

## Post-GC class-histogram comparison

Two histograms were captured during a subsequent native Eclipse JUnit run using:

```powershell
jcmd -l
jcmd <PID> GC.class_histogram > "D:\git\Exam\target\histogram-early.txt"
jcmd <PID> GC.class_histogram > "D:\git\Exam\target\histogram-late.txt"
```

Here, `<PID>` is the active `org.eclipse.jdt.internal.junit.runner.RemoteTestRunner` process ID. The histograms were taken from the same JVM, at earlier and later points during the suite. `GC.class_histogram` normally requests garbage collection before reporting live class counts.

| Class / allocation | Early | Late |
|---|---:|---:|
| `QuestionBankApplication` | 2 | 1 |
| `PdfWorkspacePane` | 2 | 1 |
| `javafx.scene.Scene` | 2 | 2 |
| `javafx.stage.Stage` | 1 | 1 |
| `com.sun.prism.Image` | 6 | 5 |
| `com.sun.prism.sw.SWRTTexture` | 239 | 796 |
| `com.sun.prism.sw.SWArgbPreTexture` | 11 | 28 |
| `int[]` shallow bytes | 326,648,848 | 880,896,136 |

The key result is that application and workspace objects did **not** accumulate during the sampled interval. This is consistent with the Stage-listener fix, and contrasts with the 207 instances of each application/workspace/Scene class observed in the failed run.

Large arrays and Prism software-render textures continued to increase across the samples. Those counts alone do **not** establish another application leak: class histograms do not show reference owners or distinguish legitimate rendering caches from leaked allocations. The residual cache behaviour may warrant a separate investigation if future test runs become unstable.

## Outcome and limitations

The originally failing workflow was restored: the native Eclipse JUnit `AllTests` suite completed within a bounded 4 GiB heap, without splitting the suite or relying on forced garbage collection. Later post-GC histograms corroborated that previously completed application instances were no longer accumulating.

Targeted lifecycle regression tests were reported green after Batch 02. This is sufficient evidence for review of Issue #147 against its acceptance criteria, subject to the usual code review and continuous-integration checks. The evidence supports resolution of the identified application-retention defect, not a claim of complete elimination of all JavaFX or Prism memory growth.

**Evidence retained in:** GitHub Issue #147 comments, the original MAT investigation, the passing-run `alltests-gc.log`, and the early/late `GC.class_histogram` reports.
