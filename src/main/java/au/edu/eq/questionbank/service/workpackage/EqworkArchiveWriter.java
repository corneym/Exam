package au.edu.eq.questionbank.service.workpackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;

/**
 * Serialises version-one .eqwork archives from portable package metadata and
 * independently supplied content streams.
 *
 * <p>
 * Sources are opened independently for each archive entry. Binary content is
 * streamed rather than loaded into a single byte array. The manifest supplies
 * the authoritative entry path, length, role and SHA-256.
 *
 * <p>
 * The output stream remains owned by the caller. A failed write can leave
 * incomplete ZIP bytes in that stream. Callers publishing to a filesystem must
 * use staging and only publish a fully written, validated archive.
 *
 * <p>
 * This codec does not read SQLite records, locate Subject files or publish
 * managed application data.
 */
public final class EqworkArchiveWriter {

	private static final int MAX_ENTRIES = 4096;
	private static final long MAX_ASSET_BYTES = 256L * 1024 * 1024;
	private static final long MAX_STRUCTURED_BYTES = 64L * 1024 * 1024;
	private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
	private final EqworkJsonlCodec jsonlCodec;
	private final EqworkManifestCodec manifestCodec;

	/**
	 * Creates a version-one archive writer.
	 */
	public EqworkArchiveWriter() {
		jsonlCodec = new EqworkJsonlCodec();
		manifestCodec = new EqworkManifestCodec();
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void requireRoleAndPath(Entry entry) throws EqworkFormatException {
		boolean permitted = switch (entry.role()) {
		case RECORDS -> entry.path().startsWith("records/") && entry.path().endsWith(".jsonl")
				&& !entry.path().equals(EqworkAssetIndex.INDEX_PATH);
		case ASSET -> entry.path().startsWith("assets/sha256/");
		case BASELINE -> entry.path().startsWith("baseline/") && entry.path().endsWith(".jsonl");
		case INDEX -> entry.path().equals(EqworkAssetIndex.INDEX_PATH);
		case RECEIPT -> entry.path().startsWith("receipts/") && entry.path().endsWith(".jsonl");
		};
		if (!permitted) {
			throw new EqworkFormatException("Incompatible archive entry role or path: " + entry.path());
		}
	}

	private static ZipArchiveEntry zipEntry(String path) {
		ZipArchiveEntry entry = new ZipArchiveEntry(path);
		entry.setMethod(ZipEntry.DEFLATED);

		// Use one fixed timestamp instead of local filesystem dates.
		entry.setTime(315532800000L);
		entry.setUnixMode(0100644);
		return entry;
	}

	/**
	 * Writes a ZIP package using the manifest's canonical entry order.
	 *
	 * <p>
	 * Every manifest entry requires exactly one supplied source. The caller retains
	 * ownership of {@code output}; each opened source stream is closed by this
	 * method.
	 *
	 * @param manifest immutable package manifest
	 * @param sources  archive path to independently openable source
	 * @param output   destination output stream
	 * @throws IOException           if reading or writing fails
	 * @throws EqworkFormatException if the content violates the contract
	 */
	public void write(EqworkManifest manifest, Map<String, EntrySource> sources, OutputStream output)
			throws IOException {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(sources, "sources");
		Objects.requireNonNull(output, "output");
		if (manifest.entries().size() + 1 > MAX_ENTRIES) {
			throw new EqworkFormatException(".eqwork archive exceeds maximum entry count");
		}
		Set<String> requiredPaths = new HashSet<>();
		long declaredTotal = 0;
		for (Entry entry : manifest.entries()) {
			requireRoleAndPath(entry);
			requiredPaths.add(entry.path());
			long maximum = entry.role() == EntryRole.ASSET ? MAX_ASSET_BYTES : MAX_STRUCTURED_BYTES;
			if (entry.size() > maximum) {
				throw new EqworkFormatException("Archive entry exceeds maximum size: " + entry.path());
			}
			declaredTotal += entry.size();
			if (declaredTotal > MAX_TOTAL_BYTES) {
				throw new EqworkFormatException("Archive exceeds maximum uncompressed size");
			}
		}
		if (!sources.keySet().equals(requiredPaths)) {
			throw new EqworkFormatException("Supplied package content disagrees with manifest entries");
		}
		for (String path : requiredPaths) {
			if (sources.get(path) == null) {
				throw new EqworkFormatException("Missing archive content source: " + path);
			}
		}

		// Verify the logical asset-reference index before emitting ZIP
		// bytes. This also rejects missing and unreferenced physical assets.
		EntrySource indexSource = sources.get(EqworkAssetIndex.INDEX_PATH);
		if (indexSource == null) {
			throw new EqworkFormatException("Missing required asset-reference index");
		}
		try (InputStream input = Objects.requireNonNull(indexSource.open(), "asset index stream")) {
			EqworkAssetIndex index = jsonlCodec.readAssetIndex(input);
			index.requireMatchingManifest(manifest);
		}

		// Reject malformed structured content before writing an archive.
		Set<String> currentStateIdentities = new HashSet<>();
		for (Entry entry : manifest.entries()) {
			if (entry.role() != EntryRole.RECORDS && entry.role() != EntryRole.BASELINE
					&& entry.role() != EntryRole.RECEIPT) {
				continue;
			}
			try (InputStream input = Objects.requireNonNull(sources.get(entry.path()).open(), "JSONL stream")) {
				for (EqworkRecord record : jsonlCodec.readRecords(input)) {
					if (entry.role() == EntryRole.RECORDS) {
						String identity = record.recordType() + "\0" + record.recordId();
						if (!currentStateIdentities.add(identity)) {
							throw new EqworkFormatException("Duplicate current-state record identity: " + identity);
						}
					}
				}
			}
		}
		ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
		manifestCodec.write(manifest, manifestBytes);
		ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output);
		zip.setEncoding("UTF-8");
		zip.setUseZip64(Zip64Mode.AsNeeded);
		writeEntry(zip, "manifest.json", manifestBytes.toByteArray());
		for (Entry entry : manifest.entries()) {
			writeEntry(zip, entry, sources.get(entry.path()));
		}
		zip.finish();
	}

	private void writeEntry(ZipArchiveOutputStream zip, Entry entry, EntrySource source) throws IOException {
		zip.putArchiveEntry(zipEntry(entry.path()));
		MessageDigest digest = newDigest();
		long count = 0;
		byte[] buffer = new byte[8192];
		try (InputStream input = Objects.requireNonNull(source.open(), "archive content stream")) {
			int length;
			while ((length = input.read(buffer)) != -1) {
				count += length;

				// Check actual bytes, not just the declared manifest size.
				if (count > entry.size()) {
					throw new EqworkFormatException("Archive source exceeds declared size: " + entry.path());
				}
				digest.update(buffer, 0, length);
				zip.write(buffer, 0, length);
			}
		}
		if (count != entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256())) {
			throw new EqworkFormatException("Archive source length or SHA-256 mismatch: " + entry.path());
		}
		zip.closeArchiveEntry();
	}

	private void writeEntry(ZipArchiveOutputStream zip, String path, byte[] contents) throws IOException {
		zip.putArchiveEntry(zipEntry(path));
		zip.write(contents);
		zip.closeArchiveEntry();
	}

	/**
	 * Opens a fresh stream for one archive-entry payload.
	 *
	 * <p>
	 * Sources must support being opened more than once because canonical structured
	 * content may be checked before archive writing.
	 */
	@FunctionalInterface
	public interface EntrySource {

		/**
		 * Opens a new input stream.
		 *
		 * @return readable stream positioned at the start of the content
		 * @throws IOException if the source cannot be opened
		 */
		InputStream open() throws IOException;
	}
}
