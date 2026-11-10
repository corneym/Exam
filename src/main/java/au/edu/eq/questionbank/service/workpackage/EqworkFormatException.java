package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;

/**
 * Reports invalid, unsupported or incompatible .eqwork package metadata.
 */
public final class EqworkFormatException extends IOException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a package-format failure.
	 *
	 * @param message description of the failure
	 */
	public EqworkFormatException(String message) {
		super(message);
	}

	/**
	 * Creates a package-format failure with an underlying cause.
	 *
	 * @param message description of the failure
	 * @param cause   underlying failure
	 */
	public EqworkFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
