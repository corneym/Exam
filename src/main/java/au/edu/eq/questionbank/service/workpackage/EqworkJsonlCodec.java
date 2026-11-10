package au.edu.eq.questionbank.service.workpackage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Strict, deterministic UTF-8 JSONL codec for version-one .eqwork records and
 * logical asset references.
 *
 * <p>
 * Every non-empty document consists of one JSON object per line. Each line ends
 * in LF, including the final line. Empty documents are permitted. Nested object
 * properties are sorted alphabetically; array positions are preserved. Limits
 * apply independently to each JSONL archive entry.
 *
 * <p>
 * This codec neither reads nor writes application-managed files. Archive
 * membership, binary bytes and package-wide limits belong to the ZIP validator.
 */
public final class EqworkJsonlCodec {

	private static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;
	private static final int MAX_LINE_BYTES = 1024 * 1024;
	private static final int MAX_LINES = 100_000;
	private final ObjectMapper mapper;

	/**
	 * Creates a strict, version-one JSONL codec.
	 */
	public EqworkJsonlCodec() {
		JsonFactory factory = JsonFactory.builder()
				.streamReadConstraints(
						StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_LINE_BYTES).build())
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
		mapper = JsonMapper.builder(factory).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
				.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
				.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
				.defaultPropertyInclusion(
						JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
				.build();
	}

	/**
	 * Reads the complete logical asset-reference index.
	 *
	 * @param input JSONL input, retained by the caller
	 * @return immutable asset index
	 * @throws IOException           if the stream cannot be read
	 * @throws EqworkFormatException if the index is invalid
	 */
	public EqworkAssetIndex readAssetIndex(InputStream input) throws IOException {
		try {
			return new EqworkAssetIndex(readLines(input, EqworkAssetIndex.Reference.class));
		} catch (IllegalArgumentException exception) {
			throw new EqworkFormatException("Invalid .eqwork asset index", exception);
		}
	}

	/**
	 * Reads, validates and canonically orders portable domain records.
	 *
	 * @param input JSONL input, retained by the caller
	 * @return immutable list of records
	 * @throws IOException           if the stream cannot be read
	 * @throws EqworkFormatException if the JSONL or record identities are invalid
	 */
	public List<EqworkRecord> readRecords(InputStream input) throws IOException {
		return normaliseRecords(readLines(input, EqworkRecord.class));
	}

	/**
	 * Writes the logical asset-reference index as deterministic UTF-8 JSONL.
	 *
	 * @param index  validated asset index
	 * @param output output stream, retained by the caller
	 * @throws IOException if serialisation or writing fails
	 */
	public void writeAssetIndex(EqworkAssetIndex index, OutputStream output) throws IOException {
		Objects.requireNonNull(index, "index");
		writeLines(index.references(), output);
	}

	/**
	 * Writes records in deterministic type-and-identity order.
	 *
	 * @param records domain records
	 * @param output  output stream, retained by the caller
	 * @throws IOException           if serialisation or writing fails
	 * @throws EqworkFormatException if record identities are duplicated
	 */
	public void writeRecords(List<EqworkRecord> records, OutputStream output) throws IOException {
		writeLines(normaliseRecords(records), output);
	}

	private JsonNode canonicalise(JsonNode node) {
		if (node.isObject()) {
			ObjectNode result = mapper.createObjectNode();
			List<String> names = new ArrayList<>();
			node.fieldNames().forEachRemaining(names::add);
			names.sort(String::compareTo);
			for (String name : names) {
				result.set(name, canonicalise(node.get(name)));
			}
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = mapper.createArrayNode();
			for (JsonNode element : node) {
				result.add(canonicalise(element));
			}
			return result;
		}
		return node.deepCopy();
	}

	private <T> T decodeLine(byte[] bytes, Class<T> type) throws EqworkFormatException {
		try {

			// Reject malformed UTF-8 rather than silently replacing invalid bytes.
			String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
			T decoded = mapper.readValue(json, type);
			if (decoded == null) {
				throw new EqworkFormatException("JSONL entries must be JSON objects");
			}
			return decoded;
		} catch (CharacterCodingException exception) {
			throw new EqworkFormatException("Invalid UTF-8 in .eqwork JSONL", exception);
		} catch (JsonProcessingException exception) {
			throw new EqworkFormatException("Malformed .eqwork JSONL record", exception);
		}
	}

	private List<EqworkRecord> normaliseRecords(List<EqworkRecord> records) throws EqworkFormatException {
		Objects.requireNonNull(records, "records");
		List<EqworkRecord> sorted = new ArrayList<>(List.copyOf(records));
		sorted.sort(
				Comparator.comparing(EqworkRecord::recordType).thenComparing(record -> record.recordId().toString()));
		Set<String> identities = new HashSet<>();
		for (EqworkRecord record : sorted) {
			String identity = record.recordType() + "\0" + record.recordId();
			if (!identities.add(identity)) {
				throw new EqworkFormatException("Duplicate portable record identity: " + identity);
			}
		}
		return List.copyOf(sorted);
	}

	private <T> List<T> readLines(InputStream input, Class<T> type) throws IOException {
		Objects.requireNonNull(input, "input");
		BufferedInputStream buffered = new BufferedInputStream(input);
		List<T> records = new ArrayList<>();
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		long totalBytes = 0;
		for (int octet; (octet = buffered.read()) != -1;) {
			totalBytes++;
			if (totalBytes > MAX_DOCUMENT_BYTES) {
				throw new EqworkFormatException(".eqwork JSONL exceeds maximum document size");
			}
			if (octet == '\r') {
				throw new EqworkFormatException(".eqwork JSONL requires LF line endings");
			}
			if (octet == '\n') {
				if (line.size() == 0) {
					throw new EqworkFormatException("Empty JSONL lines are not permitted");
				}
				if (records.size() >= MAX_LINES) {
					throw new EqworkFormatException(".eqwork JSONL exceeds maximum record count");
				}
				records.add(decodeLine(line.toByteArray(), type));
				line.reset();
			} else {
				if (line.size() >= MAX_LINE_BYTES) {
					throw new EqworkFormatException(".eqwork JSONL line exceeds maximum size");
				}
				line.write(octet);
			}
		}
		if (line.size() != 0) {
			throw new EqworkFormatException(".eqwork JSONL final line must end with LF");
		}
		return List.copyOf(records);
	}

	private <T> void writeLines(List<T> records, OutputStream output) throws IOException {
		Objects.requireNonNull(records, "records");
		Objects.requireNonNull(output, "output");
		if (records.size() > MAX_LINES) {
			throw new EqworkFormatException(".eqwork JSONL exceeds maximum record count");
		}
		long totalBytes = 0;
		for (T record : records) {
			Objects.requireNonNull(record, "record");
			JsonNode canonical = canonicalise(mapper.valueToTree(record));
			byte[] bytes = mapper.writeValueAsBytes(canonical);
			if (bytes.length > MAX_LINE_BYTES) {
				throw new EqworkFormatException(".eqwork JSONL line exceeds maximum size");
			}
			totalBytes += bytes.length + 1L;
			if (totalBytes > MAX_DOCUMENT_BYTES) {
				throw new EqworkFormatException(".eqwork JSONL exceeds maximum document size");
			}
			output.write(bytes);
			output.write('\n');
		}
	}
}
