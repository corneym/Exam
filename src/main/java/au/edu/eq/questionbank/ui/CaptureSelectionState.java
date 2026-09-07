package au.edu.eq.questionbank.ui;

/**
 * Tracks ownership of the single unaccepted selection rectangle in the PDF
 * workspace.
 * <p>
 * Accepted regions are not represented here. Once a region is accepted into a
 * capture pane, it no longer blocks page navigation.
 */
final class CaptureSelectionState {

	private CaptureSelectionOwner owner;

	/**
	 * Marks a newly completed PDF selection as belonging to the supplied capture
	 * workflow.
	 *
	 * @param owner the workflow receiving the selection
	 */
	void claim(CaptureSelectionOwner owner) {
		if (owner == null) {
			throw new NullPointerException("owner");
		}
		this.owner = owner;
	}

	/**
	 * Clears the pending selection only when the requesting workflow owns it.
	 *
	 * @param requester the workflow requesting the clear
	 * @return {@code true} when the selection was cleared
	 */
	boolean clear(CaptureSelectionOwner requester) {
		if (requester == null) {
			throw new NullPointerException("requester");
		}
		if (owner != requester) {
			return false;
		}
		owner = null;
		return true;
	}

	/**
	 * Clears any current ownership regardless of workflow.
	 * <p>
	 * Intended for application-level transitions such as replacing the displayed
	 * PDF.
	 */
	void clearAll() {
		owner = null;
	}

	/**
	 * Returns the current owner.
	 *
	 * @return the owner, or {@code null} when no selection is pending
	 */
	CaptureSelectionOwner getOwner() {
		return owner;
	}

	/**
	 * Returns whether an unaccepted selection currently exists.
	 *
	 * @return {@code true} when a workflow owns a pending selection
	 */
	boolean hasPendingSelection() {
		return owner != null;
	}

	/**
	 * Returns whether the supplied workflow owns the current pending selection.
	 *
	 * @param possibleOwner the workflow to test
	 * @return {@code true} when that workflow owns the selection
	 */
	boolean isOwnedBy(CaptureSelectionOwner possibleOwner) {
		if (possibleOwner == null) {
			throw new NullPointerException("possibleOwner");
		}
		return owner == possibleOwner;
	}
}
