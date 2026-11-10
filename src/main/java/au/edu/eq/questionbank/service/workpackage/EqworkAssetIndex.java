package au.edu.eq.questionbank.service.workpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.service.workpackage.EqworkManifest.Entry;
import au.edu.eq.questionbank.service.workpackage.EqworkManifest.EntryRole;

/**
 * Immutable, deterministically ordered references to packaged binary assets.
 *
 * <p>
 * The index is serialised as JSONL at {@code records/assets.jsonl}. Each
 * reference describes one logical use or inventoried source-file location.
 * Different references may share the same SHA-256-addressed archive entry.
 *
 * @param references logical asset references
 */
public record EqworkAssetIndex(List<Reference> references) {

	/** Required archive path for the asset-reference index. */
	public static final String INDEX_PATH = "records/assets.jsonl";

	/**
	 * Sorts references and rejects duplicate logical file ownership.
	 */
	public EqworkAssetIndex {
		List<Reference> sorted = new ArrayList<>(List.copyOf(Objects.requireNonNull(references, "references")));
		sorted.sort(Comparator.comparing(Reference::archivePath).thenComparing(Reference::managedPath)
				.thenComparing(Reference::usage)
				.thenComparing(reference -> reference.ownerType() == null ? "" : reference.ownerType())
				.thenComparing(reference -> reference.ownerId() == null ? "" : reference.ownerId().toString()));
		Set<String> logicalReferences = new HashSet<>();
		for (Reference reference : sorted) {
			String key = reference.managedPath() + "\0" + reference.usage() + "\0" + reference.ownerType() + "\0"
					+ reference.ownerId();
			if (!logicalReferences.add(key)) {
				throw new IllegalArgumentException("Duplicate logical asset reference: " + reference.managedPath());
			}
		}
		references = List.copyOf(sorted);
	}

	/**
	 * Checks that the logical asset index agrees with the manifest's physical
	 * archive inventory.
	 *
	 * <p>
	 * This check does not read archive bytes. The ZIP validator must separately
	 * verify that the actual bytes match each manifest entry's size and digest.
	 *
	 * @param manifest immutable package manifest
	 * @throws EqworkFormatException if an asset is missing, inconsistent,
	 *                               duplicated physically or unreferenced
	 */
	public void requireMatchingManifest(EqworkManifest manifest) throws EqworkFormatException {
		Objects.requireNonNull(manifest, "manifest");
		Map<String, Entry> physicalAssets = new HashMap<>();
		Map<String, String> physicalPathsByDigest = new HashMap<>();
		boolean indexPresent = false;
		for (Entry entry : manifest.entries()) {
			if (entry.path().equals(INDEX_PATH)) {
				if (entry.role() != EntryRole.INDEX) {
					throw new EqworkFormatException("Asset-reference index must have INDEX role");
				}
				indexPresent = true;
			}
			if (entry.path().startsWith("assets/") && entry.role() != EntryRole.ASSET) {
				throw new EqworkFormatException("Binary asset has incorrect manifest role: " + entry.path());
			}
			if (entry.role() != EntryRole.ASSET) {
				continue;
			}

			// Physical assets must be addressed by their own content digest.
			String prefix = "assets/sha256/" + entry.sha256().substring(0, 2) + "/" + entry.sha256() + ".";
			if (!entry.path().startsWith(prefix)) {
				throw new EqworkFormatException("Asset path does not match its SHA-256: " + entry.path());
			}
			String suffix = entry.path().substring(prefix.length());
			if (!suffix.matches("[a-z0-9]{1,8}")) {
				throw new EqworkFormatException("Invalid asset filename suffix: " + entry.path());
			}
			String previousPath = physicalPathsByDigest.putIfAbsent(entry.sha256(), entry.path());
			if (previousPath != null && !previousPath.equals(entry.path())) {
				throw new EqworkFormatException("Duplicate physical bytes must share one asset entry");
			}
			physicalAssets.put(entry.path(), entry);
		}
		if (!indexPresent) {
			throw new EqworkFormatException("Missing required asset-reference index: " + INDEX_PATH);
		}
		Set<String> referencedPaths = new HashSet<>();
		Map<String, String> mediaTypesByPath = new HashMap<>();
		for (Reference reference : references) {
			Entry entry = physicalAssets.get(reference.archivePath());
			if (entry == null) {
				throw new EqworkFormatException("Asset reference is absent from manifest: " + reference.archivePath());
			}
			if (entry.size() != reference.size() || !entry.sha256().equals(reference.sha256())) {
				throw new EqworkFormatException(
						"Asset reference size or digest disagrees with manifest: " + reference.archivePath());
			}
			String previousMediaType = mediaTypesByPath.putIfAbsent(reference.archivePath(), reference.mediaType());
			if (previousMediaType != null && !previousMediaType.equals(reference.mediaType())) {
				throw new EqworkFormatException("Conflicting media types for one physical asset");
			}
			referencedPaths.add(reference.archivePath());
		}
		for (String assetPath : physicalAssets.keySet()) {
			if (!referencedPaths.contains(assetPath)) {
				throw new EqworkFormatException("Unreferenced physical asset: " + assetPath);
			}
		}
	}

	/**
	 * One logical reference to an immutable binary asset.
	 *
	 * <p>
	 * Owner type and owner UUID are both optional for files inventoried from the
	 * managed Subject directory that are not associated with a database record. The
	 * {@code usage} field remains mandatory.
	 *
	 * @param archivePath packaged content-addressed binary path
	 * @param sha256      SHA-256 of the original bytes, lower-case hexadecimal
	 * @param size        original uncompressed byte length
	 * @param mediaType   MIME type
	 * @param managedPath original data-root-relative managed path
	 * @param ownerType   portable entity type, or null for unowned files
	 * @param ownerId     portable owner UUID, or null for unowned files
	 * @param usage       logical role of this asset
	 */
	public record Reference(String archivePath, String sha256, long size, String mediaType, String managedPath,
			String ownerType, UUID ownerId, String usage) {

		/**
		 * Validates content addressing and portable asset metadata.
		 */
		public Reference {
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid asset SHA-256");
			}
			if (archivePath == null) {
				throw new IllegalArgumentException("Missing archive asset path");
			}
			int extensionStart = archivePath.lastIndexOf('.');
			if (extensionStart < 0) {
				throw new IllegalArgumentException("Asset path needs a filename suffix");
			}
			String suffix = archivePath.substring(extensionStart + 1);
			if (!suffix.matches("[a-z0-9]{1,8}")
					|| !archivePath.equals("assets/sha256/" + sha256.substring(0, 2) + "/" + sha256 + "." + suffix)) {
				throw new IllegalArgumentException("Asset archive path must match its SHA-256");
			}
			if (size < 0) {
				throw new IllegalArgumentException("Asset size must not be negative");
			}
			if (mediaType == null || mediaType.length() > 128
					|| !mediaType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
				throw new IllegalArgumentException("Invalid asset media type");
			}
			if (("application/pdf".equals(mediaType) && !"pdf".equals(suffix))
					|| ("image/png".equals(mediaType) && !"png".equals(suffix))
					|| ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mediaType)
							&& !"xlsx".equals(suffix))) {
				throw new IllegalArgumentException("Asset filename suffix contradicts media type");
			}
			requirePortableManagedPath(managedPath);
			if ((ownerType == null) != (ownerId == null)) {
				throw new IllegalArgumentException("Asset owner type and UUID must be supplied together");
			}
			if (ownerType != null && !ownerType.matches("[A-Z][A-Z0-9_]{0,63}")) {
				throw new IllegalArgumentException("Invalid portable asset owner type");
			}
			if (usage == null || !usage.matches("[A-Z][A-Z0-9_]{0,63}")) {
				throw new IllegalArgumentException("Invalid asset usage");
			}
		}

		private static void requirePortableManagedPath(String path) {
			if (path == null || path.length() > 1024) {
				throw new IllegalArgumentException("Missing or oversized managed asset path");
			}
			String[] components = path.split("/", -1);
			if (components.length < 3 || !"subjects".equals(components[0])) {
				throw new IllegalArgumentException("Asset must have a Subject-relative managed path");
			}

			// Do not normalise untrusted paths: reject traversal and syntax
			// that could resolve differently on another operating system.
			for (String component : components) {
				if (component.isBlank() || component.equals(".") || component.equals("..") || component.endsWith(".")
						|| !component.equals(component.trim())) {
					throw new IllegalArgumentException("Unsafe managed asset path: " + path);
				}
				for (int index = 0; index < component.length(); index++) {
					char character = component.charAt(index);
					if (character < 32 || "<>:\"\\|?*".indexOf(character) >= 0) {
						throw new IllegalArgumentException("Unsafe managed asset path: " + path);
					}
				}
			}
		}
	}
}
