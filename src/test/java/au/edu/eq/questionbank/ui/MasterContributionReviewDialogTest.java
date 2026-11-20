package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.RecordAssessment;
import au.edu.eq.questionbank.service.workpackage.MasterContributionReviewPlanner.ReviewStatus;

class MasterContributionReviewDialogTest {

	@Test
	void onlySupportedReadyRecordsCanBeQueued() {
		UUID revision = UUID.randomUUID();

		RecordAssessment newQuestion = new RecordAssessment("QUESTION", UUID.randomUUID(), null, revision, null, null,
				ReviewStatus.READY_NEW);

		RecordAssessment updatedSourceQuestion = new RecordAssessment("SOURCE_QUESTION", UUID.randomUUID(), revision,
				UUID.randomUUID(), revision, "0".repeat(64), ReviewStatus.READY_UPDATE);

		RecordAssessment proposedSyllabus = new RecordAssessment("SYLLABUS_VERSION", UUID.randomUUID(), null, revision,
				null, null, ReviewStatus.READY_NEW);

		RecordAssessment staleQuestion = new RecordAssessment("QUESTION", UUID.randomUUID(), revision,
				UUID.randomUUID(), UUID.randomUUID(), "1".repeat(64), ReviewStatus.HELD_STALE_MASTER);

		RecordAssessment identicalQuestion = new RecordAssessment("QUESTION", UUID.randomUUID(), revision,
				UUID.randomUUID(), revision, "2".repeat(64), ReviewStatus.SKIP_IDENTICAL);

		assertTrue(MasterContributionReviewDialog.canQueue(newQuestion));
		assertTrue(MasterContributionReviewDialog.canQueue(updatedSourceQuestion));

		// Syllabus changes require their separate authoritative
		// approval process, even if a preliminary comparison says NEW.
		assertFalse(MasterContributionReviewDialog.canQueue(proposedSyllabus));

		// Held conflicts cannot be overridden through the UI.
		assertFalse(MasterContributionReviewDialog.canQueue(staleQuestion));

		// An identical record must be skipped, not rewritten.
		assertFalse(MasterContributionReviewDialog.canQueue(identicalQuestion));
	}

}
