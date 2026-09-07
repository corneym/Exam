package au.edu.eq.questionbank.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaptureSelectionStateTest {

	@Test
	void applicationLevelClearRemovesAnyOwner() {
		CaptureSelectionState state = new CaptureSelectionState();
		state.claim(CaptureSelectionOwner.ANSWER);
		state.clearAll();
		assertFalse(state.hasPendingSelection());
		assertNull(state.getOwner());
	}

	@Test
	void newSelectionTransfersOwnership() {
		CaptureSelectionState state = new CaptureSelectionState();
		state.claim(CaptureSelectionOwner.QUESTION);
		state.claim(CaptureSelectionOwner.SHARED_CONTEXT);
		assertFalse(state.isOwnedBy(CaptureSelectionOwner.QUESTION));
		assertTrue(state.isOwnedBy(CaptureSelectionOwner.SHARED_CONTEXT));
	}

	@Test
	void onlyOwnerCanClearPendingSelection() {
		CaptureSelectionState state = new CaptureSelectionState();
		state.claim(CaptureSelectionOwner.ANSWER);
		assertFalse(state.clear(CaptureSelectionOwner.QUESTION));
		assertTrue(state.hasPendingSelection());
		assertEquals(CaptureSelectionOwner.ANSWER, state.getOwner());
		assertTrue(state.clear(CaptureSelectionOwner.ANSWER));
		assertFalse(state.hasPendingSelection());
		assertNull(state.getOwner());
	}

	@Test
	void recordsSelectionOwner() {
		CaptureSelectionState state = new CaptureSelectionState();
		state.claim(CaptureSelectionOwner.QUESTION);
		assertTrue(state.hasPendingSelection());
		assertTrue(state.isOwnedBy(CaptureSelectionOwner.QUESTION));
		assertFalse(state.isOwnedBy(CaptureSelectionOwner.ANSWER));
		assertEquals(CaptureSelectionOwner.QUESTION, state.getOwner());
	}

	@Test
	void startsWithoutPendingSelection() {
		CaptureSelectionState state = new CaptureSelectionState();
		assertFalse(state.hasPendingSelection());
		assertNull(state.getOwner());
	}
}
