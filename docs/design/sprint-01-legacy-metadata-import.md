# Legacy Metadata Import Design

## Purpose

The existing question bank contains a Java/JavaFX workflow for capturing questions directly from examination PDFs.

There are also legacy Excel workbooks containing question metadata for several science subjects. This sprint will allow that metadata to be imported into the question bank before the corresponding PDF regions have been captured.

The imported metadata must remain compatible with questions created directly through the current capture interface.

## Domain ownership

The assessment hierarchy is:

```text
Subject
  -> Exam
       -> ExamBooklet
            -> Question
                 -> QuestionRegion
                 -> optional Answer
```

An `Exam` may contain multiple booklets.

A `Question` belongs to exactly one `ExamBooklet`.

Every `QuestionRegion` belonging to a question must come from that same booklet.

The question's original exam is obtained through its booklet:

```text
Question -> ExamBooklet -> Exam
```

A separate `exam_id` on `Question` is therefore unnecessary once the question directly stores its booklet.

## Question identity

Question numbers are identifiers within a booklet.

Examples include:

```text
1
7
21a
21b
22c
```

Question codes must always be treated as text.

The database identity constraint for a question should therefore be:

```text
(booklet_id, question_code)
```

For example, Paper 1 Question 1 and Paper 2 Question 1 are different questions belonging to the same exam.

## Question data

A question requires the following assessment metadata:

```text
id
booklet
questionCode
marks
questionText
classification
regions
answer
```

Legacy-imported questions additionally retain a preamble capture hint.

### Marks

A question has `marks`, not `maximumMarks`.

Marks are whole numbers and must be at least 1.

The application is not intended to store individual student grading against a question, so there is no need to distinguish between "marks" and "maximum marks".

No arbitrary upper limit should be imposed by the domain model.

## Question regions

`QuestionRegion` represents a rectangular portion of the source booklet PDF used to reconstruct a question.

A captured question may consist of one or several ordered regions.

When several regions exist, they are combined in their stored order to produce the complete question output.

All regions for a question come from the question's booklet.

### Questions created through the current capture interface

A question created directly through the normal PDF capture interface must contain at least one region before it can be saved.

This is a workflow requirement of the capture interface.

### Questions created by legacy metadata import

A legacy-imported question may initially contain zero regions.

Zero regions means that the question metadata has been imported but the corresponding PDF content has not yet been captured.

This is a valid intermediate state for imported questions.

The subsequent PDF-capture workflow will attach one or more ordinary `QuestionRegion` objects to the imported question.

The domain model must therefore support a persisted question with zero regions even though the normal manual capture workflow must not create one.

## Legacy workbook structure

There are multiple legacy Excel workbooks for different subjects.

The subject is import context and is not stored in each question row.

### Worksheet name

The worksheet/tab name identifies the source/provider of the exam.

Known values include:

```text
QCAA
NEAP
QTE
```

### Year

The `Year` column gives the year of the exam.

### Paper

The `Paper` column identifies the booklet.

Known legacy values are:

```text
MCQ
1
2
```

These correspond conceptually to:

```text
MCQ -> MCQ booklet
1   -> Paper 1
2   -> Paper 2
```

The importer must resolve the row to a specific `ExamBooklet`.

### Question

The `Question` column contains the question or part-question identifier.

Examples:

```text
1
21a
22b
```

This value must always be imported as text, even when Excel stores a particular cell as numeric.

### Marks

The `Marks` column supplies the question's mark value.

It becomes `Question.marks`.

### Topic

The `Topic` column contains the legacy syllabus classification.

For the currently inspected Chemistry workbooks, these values refer to the Chemistry 2019 syllabus classification structure.

Historical classifications must be preserved.

The importer must not automatically replace a historical classification with its mapped 2025 equivalent.

### Answer

For MCQ questions, the `Answer` column may contain a letter such as:

```text
A
B
C
D
```

This may be imported as a text-only `Answer`.

A blank `Answer` cell for a written-response question does not mean that the question has no answer. It means only that the legacy workbook does not supply one.

The importer must not create a fake empty `Answer`.

## Legacy preamble hint

The legacy workbook contains a `Preamble` column.

A value of `1` is capture metadata rather than a separate question-content entity.

It indicates that, when the question is subsequently captured from its source PDF, introductory or shared material should also be captured as part of the question.

The imported question may therefore initially have:

```text
preambleCaptureRequired = true
regions = []
```

The flag acts as a reminder during later PDF-region capture.

It does not imply that:

* a separate `Preamble` domain object exists;
* a `QuestionRegion` needs to be marked as a preamble;
* exactly two regions must exist permanently;
* the preamble must be stored separately from the question;
* questions created using the normal capture interface need to know about preambles.

After capture, all selected areas remain ordinary ordered `QuestionRegion` objects.

For example:

```text
Question 21a
  region 0 -> shared introductory material
  region 1 -> content specific to 21a
```

The existing region-combination/output mechanism can therefore remain unchanged.

## Shared preamble capture usability

The same introductory material may be needed by several questions or part questions.

For example:

```text
21a
21b
```

may both require the same introductory region.

A future capture-interface improvement may allow a selected region to be temporarily "pinned" and reused while capturing subsequent questions.

Pinning is a UI/workflow concept.

It does not require a persisted `pinned` property, separate preamble-region type, or shared-preamble database entity.

If the same rectangular source area is needed by two questions, each question may store ordinary region metadata referring to the same booklet page and coordinates.

## Import lifecycle

A legacy import creates question metadata before PDF-region capture.

A typical imported question is therefore initially:

```text
Question
  booklet = known
  questionCode = known
  marks = known
  classification = known
  preambleCaptureRequired = imported hint
  regions = empty
  answer = optional legacy answer
```

Later PDF capture changes the region state:

```text
regions = one or more captured QuestionRegions
```

No placeholder regions should be created during import.

## Manual capture lifecycle

A newly created question using the normal capture interface is different from a legacy-imported question.

The capture interface already has the PDF available and must require at least one selected region before saving the new question.

It does not ask whether the question has a preamble.

If several selected regions make up the question, it simply saves several ordered `QuestionRegion` objects.

## Persistence direction

The next question schema should support:

```text
questions
  id
  booklet_id
  classification_node_id
  question_code
  question_text
  marks
  preamble_capture_required
```

with:

```text
booklet_id NOT NULL
marks >= 1
preamble_capture_required boolean
UNIQUE (booklet_id, question_code)
```

`questions.exam_id` should not also be retained because the exam is already determined by `booklet_id`.

`question_regions` continue to store the ordered PDF regions for each question.

The application must permit an imported question to have no `question_regions`.

## Existing development data

Existing question capture has only been used to prove the concept and contains no important production question data.

The current development database may therefore be treated as disposable while establishing the new schema.

The design should not be weakened by inventing placeholder marks or other compatibility values solely to preserve proof-of-concept question records.

Schema migration/versioning may still be retained as an engineering mechanism, but preservation of the existing proof-of-concept question rows is not a requirement.

## Import safety

The eventual importer should:

1. Read and validate the complete workbook before writing question data.
2. Resolve the subject explicitly.
3. Resolve the historical syllabus version explicitly.
4. Resolve the worksheet name to the exam provider/source.
5. Resolve the year to the appropriate exam.
6. Resolve the `Paper` value to the appropriate booklet.
7. Treat the question code as text.
8. Validate marks as a positive whole number.
9. Resolve the legacy classification without applying a current-syllabus mapping.
10. Import MCQ answer letters where present.
11. Preserve the legacy preamble capture hint.
12. Perform the import atomically so a failed workbook does not leave a partial import.

## Outstanding design issue

The exact natural identity of an `Exam` during legacy import is not yet settled.

The workbook provides:

```text
subject
provider/source
year
```

but the existing domain also contains an `exam_name`.

Before implementing exam creation in the importer, the sprint must decide how `exam_name` is determined and whether subject + provider + year is sufficient to identify an existing exam.

This decision should be made explicitly rather than inferred from filenames or workbook structure.
