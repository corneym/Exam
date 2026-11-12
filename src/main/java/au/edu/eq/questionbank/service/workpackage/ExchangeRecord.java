package au.edu.eq.questionbank.service.workpackage;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One versioned, portable domain record in a .examwork JSONL document.
 *
 * <p>
 * The record identity and revision identities are UUIDs, never SQLite-local
 * integer identifiers. Domain values are represented by a JSON object.
 * Entity-specific field and relationship validation belongs to the later domain
 * mapping layer.
 *
 * <p>
 * The values object is copied on construction and access. Array ordering
 * remains significant, including for ordered regions and content parts.
 *
 * @param recordVersion      version of the record envelope
 * @param recordType         portable domain entity type
 * @param recordId           portable entity identity
 * @param revisionId         current revision identity, or null when unavailable
 * @param baselineRevisionId baseline revision identity, or null
 * @param values             structured domain field values
 */
public record ExchangeRecord(int recordVersion, String recordType, UUID recordId, UUID revisionId,
		UUID baselineRevisionId, JsonNode values) {

	/** Supported version of the JSONL record envelope. */
	public static final int CURRENT_RECORD_VERSION = 1;

	/**
	 * Validates the record envelope and defensively copies its values.
	 */
	public ExchangeRecord {
		if (recordVersion != CURRENT_RECORD_VERSION) {
			throw new IllegalArgumentException("Unsupported .examwork record version: " + recordVersion);
		}
		if (recordType == null || !recordType.matches("[A-Z][A-Z0-9_]{0,63}")) {
			throw new IllegalArgumentException("Invalid .examwork record type");
		}
		Objects.requireNonNull(recordId, "recordId");
		if (values == null || !values.isObject()) {
			throw new IllegalArgumentException(".examwork record values must be a JSON object");
		}
		values = values.deepCopy();
	}

	/**
	 * Returns an independent copy of the domain values.
	 *
	 * @return mutable JSON tree detached from the record
	 */
	@Override
	public JsonNode values() {
		return values.deepCopy();
	}
}
