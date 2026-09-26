package au.edu.eq.questionbank.model;

/**
 * One ordered source fragment contributing to a Question body.
 * <p>
 * A Question may mix PDF regions and stored raster images. The containing list,
 * rather than either concrete subtype, determines final assembly order.
 */
public sealed interface QuestionContentPart permits PdfQuestionContentPart, ImageQuestionContentPart {
}
