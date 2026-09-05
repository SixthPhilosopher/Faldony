-- Faldony initial schema.
-- Owned by Flyway: each environment applies V1..Vn in order exactly once.
-- Never EDIT an applied migration: always add V(n+1)__name.sql.
--
-- Type map (entity -> DDL), kept in sync by `ddl-auto: validate`:
--   Long id        -> BIGSERIAL PRIMARY KEY
--   Int            -> INTEGER
--   String         -> VARCHAR(255)
--   Instant        -> TIMESTAMPTZ
--   Boolean        -> BOOLEAN
--   enum (STRING)  -> VARCHAR(255)
--   java.sql.Timestamp -> TIMESTAMP
--   @Lob/columnDefinition="text" -> TEXT
--   @ElementCollection / @ManyToMany -> separate table
--
-- The pg-specific layer (extensions, vector indexing, FTS config) has NO
-- Hibernate equivalent and must always live here (ddl-auto: validate).

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Lightweight search config over English stemming with simple tokens.
-- Flyway applies V1 exactly once on a fresh database, so no idempotency guard
-- is needed (and no dollar-quoted DO block that script parsers could mangle).
CREATE TEXT SEARCH CONFIGURATION faldony_bi (COPY = english);
ALTER TEXT SEARCH CONFIGURATION faldony_bi
    ALTER MAPPING FOR asciiword, asciihword, hword_asciipart WITH simple;

CREATE TABLE IF NOT EXISTS person (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS person_emails (
    person_id BIGINT       NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    email     VARCHAR(255),
    CONSTRAINT uk_person_email UNIQUE (person_id, email)
);
CREATE INDEX IF NOT EXISTS idx_person_emails_person_id ON person_emails (person_id);

CREATE TABLE IF NOT EXISTS tag (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS document (
    id         BIGSERIAL PRIMARY KEY,
    file_hash  VARCHAR(255) NOT NULL UNIQUE,
    object_key VARCHAR(255),
    mime_type  VARCHAR(255) NOT NULL,
    title      VARCHAR(255) NOT NULL,
    pages      INTEGER      NOT NULL,
    type       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS document_parties (
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    person_id   BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, person_id)
);
CREATE INDEX IF NOT EXISTS idx_document_parties_person_id ON document_parties (person_id);

CREATE TABLE IF NOT EXISTS document_tags (
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    tag_id      BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_document_tags_tag_id ON document_tags (tag_id);

CREATE TABLE IF NOT EXISTS collection (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS collection_document_association (
    id            BIGSERIAL PRIMARY KEY,
    collection_id BIGINT       NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    document_id   BIGINT       NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    sort_key      VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_collection_document UNIQUE (collection_id, document_id)
);
CREATE INDEX IF NOT EXISTS idx_association_collection_order
    ON collection_document_association (collection_id, sort_key);
CREATE INDEX IF NOT EXISTS idx_association_document_id ON collection_document_association (document_id);

-- ---------------------------------------------------------------------------
-- document_chunk: the searchable unit.
--
-- embedding is HALFVEC (fp16), deliberately, not `vector` (fp32):
--   * half the index/table RAM footprint of the HNSW graph (critical: HNSW
--     performance depends on the graph staying in the page cache; the pgvector
--     docs and community benchmarks quantify ~2x memory savings),
--   * faster SIMD-vectorized distance computation (~2-5x on pure searches),
--   * recall loss vs fp32 is negligible (<0.01%) for dense e5 embeddings.
-- The ONNX model still emits fp32; the lossy downcast happens at INSERT time
-- via the `?::halfvec` casts in DocumentChunkRepository. The idx_..._embedding
-- index therefore uses halfvec_cosine_ops (the operator class must match the
-- column type AND the `<=>` operator used in queries).
-- Reverting to fp32 would require a migration + index rebuild column, never a
-- silent edit of this file once it has been applied anywhere.
--
-- tsv (full-text) is maintained by an insert/update trigger below; the
-- index/search config `faldony_bi` provides the lightweight english+numeric
-- tokenization used by the bm25 ranker.
-- ---------------------------------------------------------------------------
-- uk_document_chunk(document_id, chunk_index) below ALREADY creates a backing
-- index for the same pair; a separate idx_document_chunk_doc_index is a pure
-- storage/write-amplification duplicate and is intentionally NOT created.
CREATE TABLE IF NOT EXISTS document_chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT       NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index  INTEGER      NOT NULL,
    chunk_text   TEXT         NOT NULL,
    chunk_source VARCHAR(255),
    embedding    halfvec(768),
    tsv          tsvector,
    CONSTRAINT uk_document_chunk UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunk_tsv ON document_chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_document_chunk_trgm ON document_chunk USING gin (chunk_text gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding ON document_chunk USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_document_title_trgm ON document USING gin (title gin_trgm_ops);

-- Candidate-set / search-path indexes (type filter + ORDER BY, created_at range
-- + tiebreak, and the EXISTS probes on the many-to-many FK columns).
CREATE INDEX IF NOT EXISTS idx_document_type ON document (type);
CREATE INDEX IF NOT EXISTS idx_document_created_at ON document (created_at);

-- Refresh the FTS vector on chunk insert/update.
CREATE OR REPLACE FUNCTION document_chunk_set_tsv() RETURNS trigger AS $$
BEGIN
    NEW.tsv := to_tsvector('faldony_bi', coalesce(NEW.chunk_text, ''));
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_document_chunk_tsv ON document_chunk;
CREATE TRIGGER trg_document_chunk_tsv
    BEFORE INSERT OR UPDATE OF chunk_text ON document_chunk
    FOR EACH ROW EXECUTE FUNCTION document_chunk_set_tsv();

-- ---------------------------------------------------------------------------
-- Search knobs as DATABASE-level session defaults.
--
-- Replaces the former per-request `SET LOCAL` code in
-- DocumentChunkRepository (configureIndexScan): every pooled connection
-- inherits the GUCs on first use, so there is no per-request plumbing and
-- no silent no-op when a call runs outside a transaction.
--
-- The values are deployment constants:
--   hnsw.ef_search = 200                    HNSW candidate breadth (semantic arm)
--   hnsw.iterative_scan = 'relaxed_order'   filtered HNSW walks until LIMIT satisfied
--                                           (pgvector >= 0.8; relaxed order is safe
--                                           because the final ORDER BY is a metadata
--                                           field, not distance)
--   pg_trgm.word_similarity_threshold = 0.3 title `%>` GIN recheck threshold
--
-- ALTER DATABASE requires ownership/superuser — the compose and testcontainers
-- users are both the DB owner. current_database() keeps the target agnostic of
-- the actual database name.
DO $$
DECLARE
    db_name text := current_database();
BEGIN
    EXECUTE format('ALTER DATABASE %I SET hnsw.ef_search = 200', db_name);
    EXECUTE format('ALTER DATABASE %I SET hnsw.iterative_scan = ''relaxed_order''', db_name);
    EXECUTE format('ALTER DATABASE %I SET pg_trgm.word_similarity_threshold = 0.3', db_name);
END $$;

-- ---------------------------------------------------------------------------
-- processing_ledger: DURABLE upload-processing history.
--
-- Why (see also ProcessQueueService / ProcessController docs):
-- Temporal open-workflow visibility only shows IN-FLIGHT uploads; the moment
-- a workflow closes (success or failure) the upload disappears from the UI,
-- and closed history ages out with the retention window. This row is the
-- durable projection written BY WORKFLOW ACTIVITIES at stage boundaries:
-- STARTED/PROCESSING/EXTRACTING/EMBEDDING/INDEXING -> COMPLETED/FAILED/
-- CANCELLED/TIMED_OUT, with a TYPED error code (ProcessingErrorCode) the
-- reconciler uses to auto-heal only transient failures.
-- job_spec carries the full DocumentProcessRequest the workflow started with
-- (audit + retry-without-reupload). The reconciler GCs old rows.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processing_ledger (
    id           BIGSERIAL PRIMARY KEY,
    upload_id    VARCHAR(64)  NOT NULL UNIQUE,
    title        VARCHAR(255) NOT NULL,
    type         VARCHAR(255) NOT NULL,
    stage        VARCHAR(20)  NOT NULL,
    error_code   VARCHAR(32),
    error        TEXT,
    document_id  BIGINT,
    job_spec     TEXT,
    retry_count  INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);