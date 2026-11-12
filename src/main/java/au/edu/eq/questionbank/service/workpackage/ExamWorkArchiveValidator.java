package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.ExamWorkManifest.EntryRole;

/**
 * Performs read-only validation of version-one .examwork ZIP archives.
 *
 * <p>
 * Validation checks archive structure, entry names, Unix file types,
 * compression limits, manifest compatibility, content digests, CRC values,
 * JSONL records and logical asset references.
 *
 * <p>
 * This class never extracts an archive, opens a database or writes managed
 * application files. Validation alone does not authorise import.
 */
public final class ExamWorkArchiveValidator {

	private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
	private static final int MAX_ENTRIES = 4096;
	private static final long MAX_ASSET_BYTES = 256L * 1024 * 1024;
	private static final long MAX_MANIFEST_BYTES = 64L * 1024;
	private static final long MAX_STRUCTURED_ENTRY_BYTES = 64L * 1024 * 1024;
	private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024;
	private static final long RATIO_ALLOWANCE_BYTES = 1024L * 1024;
	private static final long MAX_COMPRESSION_RATIO = 200;
	private final ExamWorkJsonlCodec jsonlCodec;
	private final ManifestCodec manifestCodec;

	/**
	 * Creates an archive validator using the version-one metadata codecs.
	 */
	public ExamWorkArchiveValidator() {
		jsonlCodec = new ExamWorkJsonlCodec();
		manifestCodec = new ManifestCodec();
	}

	/**
	 * Validates an ExamWork archive without extracting or publishing files.
	 *
	 * @param archivePath        path to an .examwork archive
	 * @param applicationVersion installed application version
	 * @param schemaVersion      installed database schema version
	 * @return validated immutable manifest
	 * @throws IOException            if the archive cannot be read
	 * @throws PackageFormatException if the extension, archive or reader version is
	 *                                invalid
	 */
	public ExamWorkManifest validate(Path archivePath, String applicationVersion, int schemaVersion)
			throws IOException {
		Objects.requireNonNull(archivePath, "archivePath");
		Objects.requireNonNull(applicationVersion, "applicationVersion");
		if (archivePath.getFileName() == null || !archivePath.getFileName().toString().endsWith(".examwork")) {
			throw new PackageFormatException("An ExamWork archive must end in .examwork");
		}
		if (!Files.isRegularFile(archivePath, LinkOption.NOFOLLOW_LINKS)) {
			throw new PackageFormatException("Not a regular .examwork archive: " + archivePath);
		}
		if (Files.size(archivePath) > MAX_ARCHIVE_BYTES) {
			throw new PackageFormatException(".examwork archive exceeds maximum compressed size");
		}
		try (ZipFile archive = ZipFile.builder().setPath(archivePath).get()) {
			Map<String, ZipArchiveEntry> archiveEntries = inspectArchiveEntries(archive);
			ExamWorkManifest manifest = readManifest(archive, archiveEntries);
			manifest.requireCompatibleReader(applicationVersion, schemaVersion);
			Map<String, Entry> declaredEntries = checkDeclaredEntries(manifest, archiveEntries);
			checkEntryContents(archive, archiveEntries, declaredEntries);
			checkStructuredEntries(archive, manifest, archiveEntries);
			return manifest;
		}
	}

	private Map<String, Entry> checkDeclaredEntries(ExamWorkManifest manifest,
			Map<String, ZipArchiveEntry> archiveEntries) throws PackageFormatException {
		Map<String, Entry> declared = new HashMap<>();
		if (archiveEntries.size() != manifest.entries().size() + 1) {
			throw new PackageFormatException(".examwork archive entry count disagrees with manifest");
		}
		for (Entry entry : manifest.entries()) {
			requireRoleAndPath(entry);
			ZipArchiveEntry actual = archiveEntries.get(entry.path());
			if (actual == null) {
				throw new PackageFormatException("Missing declared archive entry: " + entry.path());
			}
			if (actual.getSize() != entry.size()) {
				throw new PackageFormatException("Declared archive size disagrees with ZIP entry: " + entry.path());
			}
			declared.put(entry.path(), entry);
		}
		for (String name : archiveEntries.keySet()) {
			if (!"manifest.json".equals(name) && !declared.containsKey(name)) {
				throw new PackageFormatException("Unindexed archive entry: " + name);
			}
		}
		return declared;
	}

	private void checkEntryContents(ZipFile archive, Map<String, ZipArchiveEntry> archiveEntries,
			Map<String, Entry> declaredEntries) throws IOException {
		byte[] buffer = new byte[8192];
		long cumulativeBytes = 0;
		for (ZipArchiveEntry entry : archiveEntries.values()) {
			CRC32 crc = new CRC32();
			MessageDigest digest = newDigest();
			long actualBytes = 0;
			long maximum = "manifest.json".equals(entry.getName()) ? MAX_MANIFEST_BYTES
					: entry.getName().startsWith("assets/") ? MAX_ASSET_BYTES : MAX_STRUCTURED_ENTRY_BYTES;
			try (InputStream input = archive.getInputStream(entry)) {
				int count;
				while ((count = input.read(buffer)) != -1) {
					actualBytes += count;
					cumulativeBytes += count;

					// Do not trust ZIP header lengths when reading decompressed
					// data. Enforce the same limits on the actual byte stream.
					if (actualBytes > maximum || actualBytes > entry.getSize()
							|| cumulativeBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
						throw new PackageFormatException("Excessive decompression: " + entry.getName());
					}
					crc.update(buffer, 0, count);
					digest.update(buffer, 0, count);
				}
			}
			if (actualBytes != entry.getSize()) {
				throw new PackageFormatException("ZIP entry uncompressed size mismatch: " + entry.getName());
			}
			if (crc.getValue() != entry.getCrc()) {
				throw new PackageFormatException("ZIP entry CRC mismatch: " + entry.getName());
			}
			Entry expected = declaredEntries.get(entry.getName());
			if (expected != null) {
				String actualHash = HexFormat.of().formatHex(digest.digest());
				if (actualBytes != expected.size() || !actualHash.equals(expected.sha256())) {
					throw new PackageFormatException("Archive entry SHA-256 or length mismatch: " + entry.getName());
				}
			}
		}
	}

	private void checkStructuredEntries(ZipFile archive, ExamWorkManifest manifest,
			Map<String, ZipArchiveEntry> archiveEntries) throws IOException {
		ZipArchiveEntry indexEntry = archiveEntries.get(ExamWorkAssetIndex.INDEX_PATH);
		if (indexEntry == null) {
			throw new PackageFormatException("Missing asset-reference index: " + ExamWorkAssetIndex.INDEX_PATH);
		}
		ExamWorkAssetIndex index;
		try (InputStream input = archive.getInputStream(indexEntry)) {
			index = jsonlCodec.readAssetIndex(input);
		}
		index.requireMatchingManifest(manifest);
		Set<String> recordIdentities = new HashSet<>();
		for (Entry entry : manifest.entries()) {
			if (entry.role() != EntryRole.RECORDS && entry.role() != EntryRole.BASELINE
					&& entry.role() != EntryRole.RECEIPT) {
				continue;
			}
			List<ExchangeRecord> records;
			try (InputStream input = archive.getInputStream(archiveEntries.get(entry.path()))) {
				records = jsonlCodec.readRecords(input);
			}

			// A record may appear once in the current-state collection.
			// Baseline and receipt records have distinct lifecycle roles
			// and are therefore not counted as duplicate current states.
			if (entry.role() == EntryRole.RECORDS) {
				for (ExchangeRecord record : records) {
					String identity = record.recordType() + "\0" + record.recordId();
					if (!recordIdentities.add(identity)) {
						throw new PackageFormatException("Duplicate current-state record identity: " + identity);
					}
				}
			}
		}
	}

	private Map<String, ZipArchiveEntry> inspectArchiveEntries(ZipFile archive) throws PackageFormatException {
		Map<String, ZipArchiveEntry> result = new LinkedHashMap<>();
		Set<String> portableNames = new HashSet<>();
		Enumeration<ZipArchiveEntry> entries = archive.getEntries();
		long totalDeclaredBytes = 0;
		int count = 0;
		while (entries.hasMoreElements()) {
			ZipArchiveEntry entry = entries.nextElement();
			if (++count > MAX_ENTRIES) {
				throw new PackageFormatException(".examwork archive exceeds maximum entry count");
			}
			String name = entry.getName();
			requireSafeEntryName(name);
			if (result.putIfAbsent(name, entry) != null) {
				throw new PackageFormatException("Duplicate ZIP entry name: " + name);
			}

			// A case-sensitive ZIP may otherwise contain two entries
			// that represent one destination on Windows.
			if (!portableNames.add(name.toLowerCase(Locale.ROOT))) {
				throw new PackageFormatException("Case-colliding ZIP entry name: " + name);
			}
			if (entry.isDirectory() || entry.isUnixSymlink()) {
				throw new PackageFormatException("Directories and symlinks are not permitted: " + name);
			}
			int unixType = entry.getUnixMode() & 0170000;
			if (unixType != 0 && unixType != 0100000) {
				throw new PackageFormatException("Non-regular ZIP entry: " + name);
			}
			if (entry.getGeneralPurposeBit().usesEncryption()
					|| (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
					|| !archive.canReadEntryData(entry)) {
				throw new PackageFormatException("Unsupported ZIP entry encoding: " + name);
			}
			long size = entry.getSize();
			long compressed = entry.getCompressedSize();
			long maximum = "manifest.json".equals(name) ? MAX_MANIFEST_BYTES
					: name.startsWith("assets/") ? MAX_ASSET_BYTES : MAX_STRUCTURED_ENTRY_BYTES;
			if (size < 0 || compressed < 0 || size > maximum || compressed > MAX_ARCHIVE_BYTES) {
				throw new PackageFormatException("Invalid ZIP entry size: " + name);
			}
			if (size > compressed * MAX_COMPRESSION_RATIO + RATIO_ALLOWANCE_BYTES) {
				throw new PackageFormatException("Excessive ZIP compression ratio: " + name);
			}
			if (entry.getCrc() < 0) {
				throw new PackageFormatException("Missing ZIP entry CRC: " + name);
			}
			totalDeclaredBytes += size;
			if (totalDeclaredBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
				throw new PackageFormatException(".examwork archive exceeds total decompression limit");
			}
		}
		return result;
	}

	private MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ExamWorkManifest readManifest(ZipFile archive, Map<String, ZipArchiveEntry> archiveEntries)
			throws IOException {
		ZipArchiveEntry entry = archiveEntries.get("manifest.json");
		if (entry == null) {
			throw new PackageFormatException(".examwork manifest.json is missing");
		}
		try (InputStream input = archive.getInputStream(entry)) {
			return manifestCodec.read(input);
		}
	}

	private void requireRoleAndPath(Entry entry) throws PackageFormatException {
		boolean permitted = switch (entry.role()) {
		case RECORDS -> entry.path().startsWith("records/") && entry.path().endsWith(".jsonl")
				&& !entry.path().equals(ExamWorkAssetIndex.INDEX_PATH);
		case ASSET -> entry.path().startsWith("assets/sha256/");
		case BASELINE -> entry.path().startsWith("baseline/") && entry.path().endsWith(".jsonl");
		case INDEX -> entry.path().equals(ExamWorkAssetIndex.INDEX_PATH);
		case RECEIPT -> entry.path().startsWith("receipts/") && entry.path().endsWith(".jsonl");
		};
		if (!permitted) {
			throw new PackageFormatException("Incompatible manifest entry role and path: " + entry.path());
		}
	}

	private void requireSafeEntryName(String name) throws PackageFormatException {
		if (name == null || name.isBlank() || name.length() > 256 || name.startsWith("/") || name.endsWith("/")
				|| name.contains("\\") || name.contains(":")) {
			throw new PackageFormatException("Unsafe ZIP entry name: " + name);
		}
		if ("manifest.json".equals(name)) {
			return;
		}
		boolean allowedRoot = name.startsWith("records/") || name.startsWith("assets/") || name.startsWith("baseline/")
				|| name.startsWith("receipts/");
		if (!allowedRoot) {
			throw new PackageFormatException("Unexpected ZIP entry name: " + name);
		}
		for (String component : name.split("/", -1)) {
			if (component.equals(".") || component.equals("..") || component.endsWith(".")
					|| !component.matches("[A-Za-z0-9._-]+")
					|| component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
				throw new PackageFormatException("Unsafe ZIP entry name: " + name);
			}
		}
	}
}
