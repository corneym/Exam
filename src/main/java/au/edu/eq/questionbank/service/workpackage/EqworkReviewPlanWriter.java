package au.edu.eq.questionbank.service.workpackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview.Choice;
import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview.Key;
import au.edu.eq.questionbank.service.workpackage.EqworkReconciliationReview.Snapshot;

/**
 * Writes a portable, non-authoritative coordinator review report as JSON.
 *
 * <p>
 * Reports contain package identities, original comparison evidence and the
 * coordinator's explicit decisions. They are never imported or applied by this
 * writer. Existing reports cannot be overwritten, and destinations within the
 * application's managed data root are rejected.
 */
public final class EqworkReviewPlanWriter {

	private final Path managedDataRoot;
	private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

	/**
	 * Creates a writer which protects the authoritative managed-data tree.
	 *
	 * @param managedDataRoot application's managed data root
	 */
	public EqworkReviewPlanWriter(Path managedDataRoot) {
		this.managedDataRoot = Objects.requireNonNull(managedDataRoot, "managedDataRoot").toAbsolutePath().normalize();
	}

	/**
	 * Writes the coordinator's full review, without changing source data.
	 *
	 * @param destination new JSON report path outside the managed data root
	 * @param review      complete or partial review, including original evidence
	 * @throws IOException              if the report cannot be saved safely
	 * @throws IllegalArgumentException if the destination is inside managed data
	 */
	public void write(Path destination, EqworkReconciliationReview review) throws IOException {
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(review, "review");
		Path target = destination.toAbsolutePath().normalize();
		if (!target.getFileName().toString().endsWith(".json")) {
			throw new IllegalArgumentException("The review report must have a .json filename");
		}
		if (target.startsWith(managedDataRoot)) {
			throw new IllegalArgumentException("Review reports cannot be saved inside managed data");
		}
		Path parent = target.getParent();
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
			throw new IOException("Review report parent must be an existing ordinary directory");
		}

		// Resolve every ancestor before checking that a linked directory cannot
		// redirect an apparently external report into authoritative managed data.
		if (Files.exists(managedDataRoot, LinkOption.NOFOLLOW_LINKS)
				&& parent.toRealPath().startsWith(managedDataRoot.toRealPath())) {
			throw new IllegalArgumentException("A linked report path resolves inside managed data");
		}

		// Reject existing files and use CREATE_NEW so a preview cannot overwrite
		// a previous decision report even if the path appears between checks.
		ObjectNode body = mapper.createObjectNode();
		Snapshot snapshot = review.snapshot();
		body.put("format", "eqwork-two-way-review-v1");
		body.put("subject", review.preview().subjectName());
		body.put("masterPackageId", snapshot.masterPackageId().toString());
		body.put("incomingPackageId", snapshot.incomingPackageId().toString());
		body.put("complete", snapshot.complete());
		body.put("dependencySafe", snapshot.dependencySafe());
		ArrayNode decisions = body.putArray("decisions");
		List<Key> keys = review.reviewKeys().stream().sorted(Comparator.comparing(Key::kind).thenComparing(Key::value))
				.toList();
		for (Key key : keys) {
			Choice choice = review.choice(key);
			ObjectNode row = decisions.addObject();
			row.put("kind", key.kind());
			row.put("key", key.value());
			row.put("decision", choice.decision().name());
			row.put("justification", choice.justification());
		}
		body.set("recordProposals", mapper.valueToTree(review.preview().proposals()));
		body.set("imageEvidence", mapper.valueToTree(review.preview().imageEvidence()));
		body.set("assetEvidence", mapper.valueToTree(review.preview().assetEvidence()));
		body.set("dependencies", mapper.valueToTree(review.preview().dependencies()));
		byte[] content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
		try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			output.write(content);
		}
	}
}
