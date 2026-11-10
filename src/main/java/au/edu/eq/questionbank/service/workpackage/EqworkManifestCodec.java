package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Reads and writes deterministic UTF-8 .eqwork manifest JSON.
 *
 * <p>
 * This codec validates metadata only. Archive-entry security and asset
 * verification are performed by a separate package validator.
 */
public final class EqworkManifestCodec {

	private static final int MAX_MANIFEST_BYTES = 64 * 1024;
	private final ObjectMapper mapper;

	/**
	 * Creates a strict version-one manifest codec.
	 */
	public EqworkManifestCodec() {
		JsonFactory factory = JsonFactory.builder()
				.streamReadConstraints(
						StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_MANIFEST_BYTES).build())
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
		mapper = new ObjectMapper(factory);
		mapper.registerModule(new JavaTimeModule());
		mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
		mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
	}

	/**
	 * Reads one bounded, strictly validated JSON manifest.
	 *
	 * <p>
	 * The caller retains ownership of the input stream.
	 *
	 * @param input manifest source
	 * @return validated immutable manifest
	 * @throws IOException           if reading fails
	 * @throws EqworkFormatException if metadata is malformed or unsupported
	 */
	public EqworkManifest read(InputStream input) throws IOException {
		Objects.requireNonNull(input, "input");
		byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
		if (bytes.length > MAX_MANIFEST_BYTES) {
			throw new EqworkFormatException(".eqwork manifest exceeds maximum permitted size");
		}
		try {

			// Decode strictly as UTF-8; do not accept replacement characters
			// or a silently detected alternative JSON encoding.
			String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
			EqworkManifest manifest = mapper.readValue(json, EqworkManifest.class);
			if (manifest == null) {
				throw new EqworkFormatException(".eqwork manifest must be a JSON object");
			}
			return manifest;
		} catch (CharacterCodingException exception) {
			throw new EqworkFormatException(".eqwork manifest is not valid UTF-8", exception);
		} catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
			throw new EqworkFormatException("Invalid .eqwork manifest JSON", exception);
		}
	}

	/**
	 * Writes an immutable manifest in deterministic UTF-8 JSON form.
	 *
	 * <p>
	 * Object properties, scope identities and archive entries have deterministic
	 * ordering. The output ends with one newline. The caller retains ownership of
	 * the output stream.
	 *
	 * @param manifest validated manifest
	 * @param output   destination stream
	 * @throws IOException if writing fails
	 */
	public void write(EqworkManifest manifest, OutputStream output) throws IOException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(output, "output");
		byte[] json = mapper.writeValueAsBytes(manifest);
		if (json.length + 1 > MAX_MANIFEST_BYTES) {
			throw new EqworkFormatException(".eqwork manifest exceeds maximum permitted size");
		}
		output.write(json);
		output.write('\n');
	}
}
