package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqlitePortableAliasRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;

/**
 * Resolves originating Subject record identities against approved destination
 * aliases.
 *
 * <p>
 * Resolution is read-only and must use a caller-owned transaction. Existing
 * aliases must identify active canonical records in the represented destination
 * Subject graph.
 *
 * <p>
 * Unmapped originating identities retain their original UUID as a provisional
 * comparison identity, provided that UUID has never been allocated in the
 * destination.
 */
public final class SubjectRefreshAliasResolver {

	private final SqlitePortableAliasRepository aliases;
	private final SqlitePortableIdentityRepository identities;

	/**
	 * Creates a resolver for an initialised destination database.
	 *
	 * @param database destination database
	 * @throws NullPointerException if database is null
	 */
	public SubjectRefreshAliasResolver(SqliteDatabase database) {
		Objects.requireNonNull(database, "database");
		aliases = new SqlitePortableAliasRepository(database);
		identities = new SqlitePortableIdentityRepository(database);
	}

	/**
	 * Resolves Subject-owned originating records against the current destination
	 * graph.
	 *
	 * <p>
	 * Shared exam providers must be resolved separately. This method rejects
	 * incoming provider records rather than implicitly granting authority over
	 * globally shared records.
	 *
	 * @param connection           caller-owned transactional connection
	 * @param originInstallationId originating installation identity
	 * @param current              current destination Subject records
	 * @param incoming             incoming Subject-owned records
	 * @return immutable originating-to-comparison identity mappings
	 * @throws SQLException           if database identity lookup fails
	 * @throws PackageFormatException if identity resolution is unsafe
	 * @throws IllegalStateException  if no caller-owned transaction exists
	 * @throws NullPointerException   if an argument is null
	 */
	public Map<SubjectRefreshChangeClassifier.RecordKey, UUID> resolve(Connection connection, UUID originInstallationId,
			List<ExchangeRecord> current, List<ExchangeRecord> incoming) throws SQLException, PackageFormatException {
		Objects.requireNonNull(connection, "connection");
		Objects.requireNonNull(originInstallationId, "originInstallationId");
		Objects.requireNonNull(current, "current");
		Objects.requireNonNull(incoming, "incoming");
		if (connection.getAutoCommit()) {
			throw new IllegalStateException(
					"Subject refresh alias resolution requires " + "a caller-owned transaction");
		}
		Set<SubjectRefreshChangeClassifier.RecordKey> currentKeys = new HashSet<>();
		Set<UUID> currentIds = new HashSet<>();
		for (ExchangeRecord record : current) {
			var key = new SubjectRefreshChangeClassifier.RecordKey(record.recordType(), record.recordId());
			if (!currentKeys.add(key) || !currentIds.add(record.recordId())) {
				throw new PackageFormatException("Duplicate canonical record identity: " + record.recordId());
			}
		}
		Map<SubjectRefreshChangeClassifier.RecordKey, UUID> mappings = new HashMap<>();
		Set<UUID> resolvedIds = new HashSet<>();
		for (ExchangeRecord record : incoming) {
			var sourceKey = new SubjectRefreshChangeClassifier.RecordKey(record.recordType(), record.recordId());
			if (mappings.containsKey(sourceKey)) {
				throw new PackageFormatException("Duplicate originating record: " + sourceKey);
			}
			var entityType = requireEntityType(record.recordType());
			if (entityType == SqlitePortableIdentityRepository.EntityType.EXAM_PROVIDER) {
				throw new PackageFormatException("Shared exam providers require separate " + "refresh reconciliation");
			}
			Optional<UUID> alias = aliases.findAlias(connection, originInstallationId, entityType, record.recordId());
			UUID target;
			if (alias.isPresent()) {
				target = alias.orElseThrow();
				requireActiveGraphIdentity(connection, entityType, target, currentKeys);
			} else {

				// Matching UUIDs alone do not establish authority.
				// Retired identities must also remain reserved.
				target = record.recordId();
				if (currentIds.contains(target) || hasAllocatedIdentity(connection, target)) {
					throw new PackageFormatException("Unmapped originating identity " + "collides with an allocated "
							+ "destination identity: " + target);
				}
				requireUnusedOriginIdentity(connection, originInstallationId, target);
			}
			if (!resolvedIds.add(target)) {
				throw new PackageFormatException(
						"Multiple originating records resolve " + "to one destination identity: " + target);
			}
			mappings.put(sourceKey, target);
		}
		return Map.copyOf(mappings);
	}

	private boolean hasAllocatedIdentity(Connection connection, UUID portableId) throws SQLException {

		// Deleted identities are transferred to the permanent
		// retirement ledger. Both tables must be checked to
		// prevent reuse of previously allocated portable UUIDs.
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT 1
				FROM portable_entity_identities
				WHERE portable_id = ?

				UNION ALL

				SELECT 1
				FROM portable_entity_retirements
				WHERE portable_id = ?

				LIMIT 1
				""")) {
			statement.setString(1, portableId.toString());
			statement.setString(2, portableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private void requireActiveGraphIdentity(Connection connection,
			SqlitePortableIdentityRepository.EntityType entityType, UUID canonicalId,
			Set<SubjectRefreshChangeClassifier.RecordKey> currentKeys) throws SQLException, PackageFormatException {
		var identity = identities.findByPortableId(connection, entityType, canonicalId);
		if (identity.isEmpty()) {
			throw new PackageFormatException(
					"Origin alias points to a missing or " + "retired canonical identity: " + canonicalId);
		}
		var key = new SubjectRefreshChangeClassifier.RecordKey(entityType.name(), canonicalId);
		if (!currentKeys.contains(key)) {
			throw new PackageFormatException(
					"Origin alias points outside the current " + "destination Subject graph: " + canonicalId);
		}
	}

	private SqlitePortableIdentityRepository.EntityType requireEntityType(String recordType)
			throws PackageFormatException {
		try {
			return SqlitePortableIdentityRepository.EntityType.valueOf(recordType);
		} catch (IllegalArgumentException failure) {
			throw new PackageFormatException("Unsupported Subject record type: " + recordType, failure);
		}
	}

	private void requireUnusedOriginIdentity(Connection connection, UUID originInstallationId, UUID portableId)
			throws SQLException, PackageFormatException {

		// A previously approved UUID cannot reappear under
		// a different entity type.
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT entity_type
				FROM portable_identity_aliases
				WHERE origin_installation_id = ?
				  AND origin_portable_id = ?
				LIMIT 1
				""")) {
			statement.setString(1, originInstallationId.toString());
			statement.setString(2, portableId.toString());
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) {
					throw new PackageFormatException("Origin identity already belongs to "
							+ "another approved entity type: " + result.getString("entity_type"));
				}
			}
		}
	}
}
