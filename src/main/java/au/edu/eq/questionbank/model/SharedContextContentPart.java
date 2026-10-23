package au.edu.eq.questionbank.model;

/**
 * One source fragment belonging to reusable Shared Context content.
 * <p>
 * PDF regions and stored PNG images may be interleaved. Their position in the
 * containing list determines their display and persistence order.
 */
public sealed interface SharedContextContentPart permits SharedQuestionContextRegion, ImageSharedContextContentPart {
}
