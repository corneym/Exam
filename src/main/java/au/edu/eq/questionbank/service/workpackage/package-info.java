/**
 * Version-one portable offline exchange contract for Exam Question Bank.
 *
 * <h2>Container</h2>
 *
 * <p>
 * A {@code .eqwork} package is a ZIP archive with one {@code manifest.json} at
 * its root. Every other physical file is declared exactly once in the
 * manifest's {@code entries} collection. Directory entries, symbolic links,
 * unsafe paths and unindexed files are forbidden.
 *
 * <p>
 * Supported archive paths:
 * <ul>
 * <li>{@code manifest.json}: mandatory versioned manifest.</li>
 * <li>{@code records/assets.jsonl}: mandatory logical binary asset index.</li>
 * <li>{@code records/*.jsonl}: portable current-state domain records.</li>
 * <li>{@code baseline/*.jsonl}: immutable baseline record values.</li>
 * <li>{@code receipts/*.jsonl}: acknowledged import outcomes.</li>
 * <li>{@code assets/sha256/xx/digest.suffix}: immutable binary content.</li>
 * </ul>
 *
 * <h2>Manifest metadata</h2>
 *
 * <p>
 * Required fields: {@code formatVersion}, {@code packageId},
 * {@code packageType}, {@code createdAt}, {@code originInstallationId},
 * {@code subjectId}, {@code subjectName}, {@code producerApplicationVersion},
 * {@code minimumReaderApplicationVersion}, {@code producerSchemaVersion},
 * {@code minimumReaderSchemaVersion}, {@code scope} and {@code entries}.
 *
 * <p>
 * Conditional fields: {@code intendedMasterInstallationId},
 * {@code assignmentId} and {@code baselineSnapshotId}. Assignments and
 * contributions require all three. Import receipts require a master
 * installation identity. Subject snapshots may omit all three.
 *
 * <p>
 * The format version is independently versioned from the application and SQLite
 * database schema. Version one requires {@code formatVersion = 1}.
 * Compatibility is checked against the explicitly declared minimum reader
 * application and schema versions. Producer versions identify provenance.
 *
 * *
 * <p>
 * The {@code subjectName} must be one valid, portable managed-directory
 * component. Path separators, traversal components, reserved Windows device
 * names, trailing dots, and leading or trailing whitespace are forbidden.
 *
 * <h2>Package purposes</h2>
 *
 * <ul>
 * <li>{@code SUBJECT_SNAPSHOT}: a portable Subject dataset.</li>
 * <li>{@code WORK_ASSIGNMENT}: coordinator-issued offline work with an
 * assignment identity and baseline identity.</li>
 * <li>{@code CONTRIBUTION}: contributor work returned against an identified
 * assignment and baseline.</li>
 * <li>{@code IMPORT_RECEIPT}: coordinator acknowledgement of an import,
 * including accepted, skipped, rejected or conflicted outcomes.</li>
 * </ul>
 *
 * <p>
 * An assignment is not a lock. Partial returns may occur repeatedly. An import
 * receipt does not automatically complete the assignment. Baseline snapshots
 * are immutable and must remain available for later three-way reconciliation. A
 * baseline identifier alone does not prove that the underlying baseline values
 * are available.
 *
 * <h2>Portable domain records</h2>
 *
 * <p>
 * Every JSONL record uses {@code recordVersion}, {@code recordType},
 * {@code recordId}, optional {@code revisionId}, optional
 * {@code baselineRevisionId}, and the structured {@code values} object. Record
 * identities and revision identities are UUIDs. Application-local SQLite row
 * identifiers and foreign-key integers are not portable record identities.
 *
 * <p>
 * JSONL is UTF-8 with one JSON object per LF-terminated line and no blank
 * lines. Object property names and records are sorted deterministically. Array
 * order is preserved. Optional null envelope properties are omitted. A document
 * may be empty.
 *
 * <p>
 * The package layer validates record-envelope structure, canonical
 * serialisation and duplicate identities. Domain-specific relationship schemas,
 * referential mapping and merge semantics are defined by the later Subject
 * export/import and reconciliation work.
 *
 * <h2>Asset references</h2>
 *
 * <p>
 * {@code records/assets.jsonl} contains one logical reference per owner,
 * managed path and usage. A reference includes: {@code archivePath},
 * {@code sha256}, {@code size}, {@code mediaType}, {@code managedPath},
 * optional {@code ownerType}, optional {@code ownerId}, and {@code usage}.
 *
 * <p>
 * Both owner fields are absent for inventoried files without a database owner,
 * including retained legacy workbooks. Managed paths are relative to the
 * application data root, use forward slashes and begin with
 * {@code subjects/SubjectName/}. They must belong to the manifest Subject.
 *
 * <p>
 * Identical binary bytes share one physical archive entry, regardless of the
 * number of logical references. Physical assets use lower-case SHA-256 content
 * addresses and retain their original byte length. Source PDFs, syllabus PDFs
 * and XLSX workbooks remain separate binary archive files; they are not
 * embedded in SQLite or JSONL BLOB fields.
 *
 * <h2>Validation and publication</h2>
 *
 * <p>
 * Validation is read-only. The archive must be fully checked for duplicate
 * paths, traversal, symbolic links, unsupported entry encodings, missing
 * entries, size and checksum mismatches, incompatible versions, malformed
 * JSONL, asset-index inconsistencies, and archive resource limits before any
 * import may be considered.
 *
 * <p>
 * A successful package validation is not an authorisation to import. Persistent
 * identity mapping, conflict detection, import approval, transaction handling
 * and managed-file publication are separate responsibilities.
 *
 * @see au.edu.eq.questionbank.service.workpackage.EqworkManifest
 * @see au.edu.eq.questionbank.service.workpackage.EqworkJsonlCodec
 * @see au.edu.eq.questionbank.service.workpackage.EqworkArchiveValidator
 */
package au.edu.eq.questionbank.service.workpackage;
