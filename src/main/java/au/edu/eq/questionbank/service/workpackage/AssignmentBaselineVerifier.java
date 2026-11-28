package au.edu.eq.questionbank.service.workpackage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import au.edu.eq.questionbank.repository.identity.SqliteExamAssignmentGraphReader;
import au.edu.eq.questionbank.repository.identity.SqlitePortableContentRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.SubjectReconciliationReview.Key;

/**
 * Verifies that an installed Exam graph exactly matches its issued baseline.
 */
final class AssignmentBaselineVerifier {

	private AssignmentBaselineVerifier() {
	}

	static void requireComplete(Connection connection, SqliteDatabase database, long subjectId, long examId,
			List<ExchangeRecord> expected) throws SQLException {
		Map<Key, ExchangeRecord> actual = readCurrentRecords(connection, database, subjectId, examId);
		Map<Key, ExchangeRecord> required = index(expected);
		if (!required.keySet().equals(actual.keySet())) {
			throw new SQLException("Installed Exam graph has missing or unexpected records");
		}
		for (Key key : required.keySet()) {
			if (!required.get(key).equals(actual.get(key))) {
				throw new SQLException("Installed Exam differs from authoritative baseline: " + key);
			}
		}
	}

	private static Map<Key, ExchangeRecord> index(List<ExchangeRecord> records) throws SQLException {
		Map<Key, ExchangeRecord> result = new LinkedHashMap<>();
		for (ExchangeRecord record : records) {
			Key key = SubjectReconciliationReview.recordKey(record.recordType(), record.recordId());
			if (result.putIfAbsent(key, record) != null) {
				throw new SQLException("Duplicate portable record: " + key);
			}
		}
		return result;
	}

	private static Map<Key, ExchangeRecord> readCurrentRecords(Connection connection, SqliteDatabase database,
			long subjectId, long examId) throws SQLException {
		var graph = SqliteExamAssignmentGraphReader.readInTransaction(connection, subjectId, examId);
		var content = new SqlitePortableContentRepository(database);
		List<ExchangeRecord> records = new ArrayList<>();
		for (EntityType type : EntityType.values()) {
			for (long localId : graph.ids(type)) {
				records.add(content.snapshot(connection, type, localId).record());
			}
		}
		return index(records);
	}
}
