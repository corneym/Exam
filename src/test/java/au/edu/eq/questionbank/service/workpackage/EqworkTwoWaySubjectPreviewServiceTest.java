package au.edu.eq.questionbank.service.workpackage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import au.edu.eq.questionbank.ManagedDataLayout;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository;
import au.edu.eq.questionbank.repository.identity.SqlitePortableIdentityRepository.EntityType;
import au.edu.eq.questionbank.repository.sqlite.SqliteDatabase;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Classification;
import au.edu.eq.questionbank.service.workpackage.EqworkCurriculumReconciler.Proposal;

/**
 * True two-database integration tests for read-only Subject reconciliation.
 *
 * <p>
 * Each fixture initialises its own current SQLite schema, independent portable
 * installation identities and Subject-first managed PDF directory. Identical
 * local integer IDs deliberately refer to independently created portable UUIDs.
 * Assertions compare real exported, validated archives rather than hand-crafted
 * JSON objects, and recheck both source databases and original PDF bytes.
 */
class EqworkTwoWaySubjectPreviewServiceTest {

    @TempDir
    Path temporary;

    /** Confirms two installations with colliding integer IDs can still match logical records. */
    @Test
    void independentlyInitialisedDatabasesMatchByEvidenceNotSqliteIds() throws Exception {
        Fixture master = seed("master", "Unit 1", "1", "Original question", false);
        Fixture incoming = seed("incoming", "Unit 1", "1", "Original question", false);
        var masterIdentity = new SqlitePortableIdentityRepository(master.database())
                .requireByLocalId(EntityType.QUESTION, 81).portableId();
        var incomingIdentity = new SqlitePortableIdentityRepository(incoming.database())
                .requireByLocalId(EntityType.QUESTION, 81).portableId();
        assertNotEquals(masterIdentity, incomingIdentity);
        byte[] masterPdf = Files.readAllBytes(master.pdf());
        byte[] incomingPdf = Files.readAllBytes(incoming.pdf());
        int masterCount = questionCount(master.database());
        int incomingCount = questionCount(incoming.database());

        var result = service().preview(export(master), export(incoming));

        assertEquals(Classification.IDENTICAL, single(result, "SUBJECT").classification());
        assertEquals(Classification.IDENTICAL, single(result, "SYLLABUS_VERSION").classification());
        assertEquals(Classification.IDENTICAL, single(result, "EXAM").classification());
        assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
        assertEquals(Classification.IDENTICAL, single(result, "QUESTION").classification());
        assertTrue(single(result, "QUESTION").proposesAlias());
        assertEquals(1, result.assetEvidence().size());
        assertEquals(Classification.IDENTICAL, result.assetEvidence().getFirst().classification());
        assertEquals(masterCount, questionCount(master.database()));
        assertEquals(incomingCount, questionCount(incoming.database()));
        assertArrayEquals(masterPdf, Files.readAllBytes(master.pdf()));
        assertArrayEquals(incomingPdf, Files.readAllBytes(incoming.pdf()));
    }

    /** Confirms the complete Biology 2019 hierarchy must match, not merely the syllabus title. */
    @Test
    void differentCurriculumStructuresRemainConflicts() throws Exception {
        Fixture master = seed("master-structure", "Unit 1", "1", "Original question", false);
        Fixture incoming = seed("incoming-structure", "Unit 1", "1", "Original question", true);

        var result = service().preview(export(master), export(incoming));

        assertEquals(Classification.CONFLICT, single(result, "SYLLABUS_VERSION").classification());
        assertTrue(result.forType("CURRICULUM_NODE").stream()
                .anyMatch(proposal -> proposal.classification() != Classification.IDENTICAL));
    }

    /** Confirms the same byte-identical PDF does not erase a differently captured Question. */
    @Test
    void duplicatePdfsWithDifferentQuestionContentAreReviewable() throws Exception {
        Fixture master = seed("master-question", "Unit 1", "1", "Original question", false);
        Fixture incoming = seed("incoming-question", "Unit 1", "1", "Revised question", false);

        var result = service().preview(export(master), export(incoming));

        assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
        assertEquals(Classification.CONFLICT, single(result, "QUESTION").classification());
        assertEquals(1, questionCount(master.database()));
        assertEquals("Original question", questionText(master.database()));
    }

    /** Confirms a new Question beneath an identical Booklet remains a compatible addition. */
    @Test
    void compatibleIndependentCaptureIsProposedWithoutReplacingMasterQuestion() throws Exception {
        Fixture master = seed("master-addition", "Unit 1", "1", "Original question", false);
        Fixture incoming = seed("incoming-addition", "Unit 1", "2", "Another question", false);

        var result = service().preview(export(master), export(incoming));

        assertEquals(Classification.IDENTICAL, single(result, "EXAM_BOOKLET").classification());
        assertEquals(Classification.COMPATIBLE_ADDITION, single(result, "QUESTION").classification());
        assertEquals(null, single(result, "QUESTION").masterId());
        assertEquals("Original question", questionText(master.database()));
    }

    /** Confirms a changed source document is not silently aliased by the matching Booklet name. */
    @Test
    void changedPdfBytesBlockBookletEquivalence() throws Exception {
        Fixture master = seed("master-pdf", "Unit 1", "1", "Original question", false);
        Fixture incoming = seed("incoming-pdf", "Unit 1", "1", "Original question", false);
        Files.writeString(incoming.pdf(), "DIFFERENT SOURCE PDF", StandardCharsets.UTF_8);

        var result = service().preview(export(master), export(incoming));

        assertEquals(Classification.CONFLICT, single(result, "EXAM_BOOKLET").classification());
        assertTrue(result.assetEvidence().stream()
                .anyMatch(asset -> asset.classification() == Classification.CONFLICT));
        assertEquals("CAPTURE PDF", Files.readString(master.pdf(), StandardCharsets.UTF_8));
    }

    /** Confirms package validation and Subject identity are enforced before matching. */
    @Test
    void rejectsAnUntrustedArchiveRatherThanReadingUnvalidatedRecords() throws Exception {
        Fixture master = seed("trusted", "Unit 1", "1", "Original question", false);
        Path fake = temporary.resolve("fake.eqwork");
        Files.writeString(fake, "not a ZIP", StandardCharsets.UTF_8);

        assertThrows(Exception.class, () -> service().preview(export(master), fake));
        assertFalse(Files.size(fake) == 0);
    }

    /** Creates a genuine current-schema SQLite fixture with independent installation identity. */
    private Fixture seed(String name, String unitName, String code, String questionText,
            boolean extraCurriculumNode) throws Exception {
        Path root = temporary.resolve(name);
        Files.createDirectories(root);
        SqliteDatabase database = new SqliteDatabase(root.resolve("questionbank.db"));
        database.initialiseSchema();
        ManagedDataLayout layout = new ManagedDataLayout(root);
        Path pdf = layout.resolve("subjects/Biology/exams/questions.pdf");
        Files.createDirectories(pdf.getParent());
        Files.writeString(pdf, "CAPTURE PDF", StandardCharsets.UTF_8);
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO subjects(id,subject_name) VALUES(1,'Biology')");
            statement.execute("""
                    INSERT INTO syllabus_versions(id,subject_id,syllabus_name,is_current)
                    VALUES(11,1,'Biology 2019',1)
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO curriculum_nodes(id,syllabus_version_id,parent_id,curriculum_code,
                        curriculum_name,curriculum_level,display_order)
                    VALUES(101,11,NULL,'UNIT1',?,'UNIT',0)
                    """)) {
                insert.setString(1, unitName);
                insert.executeUpdate();
            }
            if (extraCurriculumNode) {
                statement.execute("""
                        INSERT INTO curriculum_nodes(id,syllabus_version_id,parent_id,curriculum_code,
                            curriculum_name,curriculum_level,display_order)
                        VALUES(102,11,101,'TOPIC1','Different topic','TOPIC',1)
                        """);
            }
            statement.execute("INSERT INTO exam_providers(id,provider_name) VALUES(1,'QCAA')");
            statement.execute("""
                    INSERT INTO source_documents(id,relative_path)
                    VALUES(51,'subjects/Biology/exams/questions.pdf')
                    """);
            statement.execute("""
                    INSERT INTO exams(id,subject_id,provider_id,exam_year,exam_name,
                        capture_state,expected_question_booklet_count)
                    VALUES(31,1,1,2024,'External Assessment','ACTIVE',1)
                    """);
            statement.execute("""
                    INSERT INTO exam_booklets(id,exam_id,source_document_id,booklet_name,legacy_paper_code)
                    VALUES(61,31,51,'Paper 1','1')
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO questions(id,booklet_id,classification_node_id,question_code,
                        question_text,marks,shared_context_capture_required)
                    VALUES(81,61,101,?,?,2,0)
                    """)) {
                insert.setString(1, code);
                insert.setString(2, questionText);
                insert.executeUpdate();
            }
            statement.execute("""
                    INSERT INTO question_regions(question_id,region_order,booklet_id,page_number,x,y,width,height)
                    VALUES(81,0,61,2,0.1,0.2,0.5,0.3)
                    """);
            statement.execute("""
                    INSERT INTO question_content_parts(question_id,content_order,content_type,region_order)
                    VALUES(81,0,'PDF_REGION',0)
                    """);
        }
        return new Fixture(database, layout, pdf, root.resolve(name + ".eqwork"));
    }

    /** Produces the actual validated portable snapshot from the given database. */
    private Path export(Fixture fixture) throws Exception {
        new EqworkSubjectSnapshotExporter(fixture.database(), fixture.layout()).export(1, fixture.archive());
        return fixture.archive();
    }

    /** Counts persisted Questions without using the exporter cache. */
    private int questionCount(SqliteDatabase database) throws Exception {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM questions")) {
            result.next();
            return result.getInt(1);
        }
    }

    /** Reads the authoritative Question text after a read-only preview. */
    private String questionText(SqliteDatabase database) throws Exception {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT question_text FROM questions WHERE id=81")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    /** Selects the single incoming proposal of a portable entity type. */
    private Proposal single(EqworkTwoWaySubjectPreviewService.Result result, String type) {
        assertEquals(1, result.forType(type).size());
        return result.forType(type).getFirst();
    }

    /** Creates the production two-way preview service. */
    private EqworkTwoWaySubjectPreviewService service() {
        return new EqworkTwoWaySubjectPreviewService();
    }

    /** Isolates the two independent database and filesystem fixture locations. */
    private record Fixture(SqliteDatabase database, ManagedDataLayout layout, Path pdf, Path archive) {
    }
}
