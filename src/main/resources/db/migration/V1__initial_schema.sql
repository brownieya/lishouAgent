-- lishouAgent initial schema, PostgreSQL 17 with pgvector.
-- Flyway applies this migration inside the already-created lishou_agent database.
-- NEVER edit an applied versioned migration; add V2/V3 migrations for later changes.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE public.app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(100) NOT NULL,
    display_name varchar(160) NOT NULL,
    password_hash varchar(255),
    external_subject varchar(255),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT app_user_username_not_blank CHECK (length(trim(username)) > 0),
    CONSTRAINT app_user_identity_required CHECK (
        password_hash IS NOT NULL OR external_subject IS NOT NULL)
);
CREATE UNIQUE INDEX app_user_username_lower_uq ON public.app_user (lower(username));
CREATE UNIQUE INDEX app_user_external_subject_uq ON public.app_user (external_subject)
    WHERE external_subject IS NOT NULL;

CREATE TABLE public.chat_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES public.app_user(id),
    title varchar(200) NOT NULL DEFAULT '新对话',
    status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz
);
CREATE INDEX chat_session_user_recent_idx ON public.chat_session (user_id, updated_at DESC)
    WHERE status <> 'DELETED';

CREATE TABLE public.chat_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES public.chat_session(id) ON DELETE CASCADE,
    sequence_no bigint NOT NULL CHECK (sequence_no > 0),
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    content text NOT NULL DEFAULT '',
    status varchar(20) NOT NULL DEFAULT 'COMPLETE'
        CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETE', 'FAILED', 'INTERRUPTED', 'CANCELLED')),
    model varchar(100),
    input_tokens integer CHECK (input_tokens >= 0),
    output_tokens integer CHECK (output_tokens >= 0),
    error_code varchar(100),
    error_message text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    CONSTRAINT chat_message_session_sequence_uq UNIQUE (session_id, sequence_no)
);
CREATE INDEX chat_message_incomplete_idx ON public.chat_message (updated_at)
    WHERE status IN ('PENDING', 'GENERATING');

CREATE TABLE public.knowledge_document (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key varchar(512) NOT NULL UNIQUE,
    title varchar(500) NOT NULL CHECK (length(trim(title)) > 0),
    project_name varchar(200),
    source_url text,
    active_version_id uuid,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    created_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    updated_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz
);
CREATE INDEX knowledge_document_project_recent_idx
    ON public.knowledge_document (project_name, updated_at DESC) WHERE status <> 'DELETED';

CREATE TABLE public.knowledge_document_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES public.knowledge_document(id) ON DELETE CASCADE,
    version_no integer NOT NULL CHECK (version_no > 0),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    original_filename varchar(500) NOT NULL,
    file_format varchar(20) NOT NULL DEFAULT 'MARKDOWN'
        CHECK (file_format IN ('MARKDOWN', 'HTML', 'PDF')),
    storage_path text NOT NULL,
    parsed_content text,
    status varchar(20) NOT NULL DEFAULT 'IMPORTED'
        CHECK (status IN ('IMPORTED', 'PARSING', 'INDEXING', 'READY', 'ACTIVE', 'FAILED', 'SUPERSEDED')),
    source_updated_at timestamptz,
    created_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    error_message text,
    CONSTRAINT knowledge_document_version_no_uq UNIQUE (document_id, version_no),
    CONSTRAINT knowledge_document_version_document_uq UNIQUE (id, document_id)
);
CREATE INDEX knowledge_document_version_hash_idx
    ON public.knowledge_document_version (document_id, content_sha256);
CREATE UNIQUE INDEX knowledge_document_one_active_version_uq
    ON public.knowledge_document_version (document_id) WHERE status = 'ACTIVE';
ALTER TABLE public.knowledge_document ADD CONSTRAINT knowledge_document_active_version_fk
    FOREIGN KEY (active_version_id, id)
    REFERENCES public.knowledge_document_version (id, document_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.knowledge_asset (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id uuid NOT NULL REFERENCES public.knowledge_document_version(id) ON DELETE CASCADE,
    relative_path text NOT NULL,
    storage_path text NOT NULL,
    media_type varchar(100) NOT NULL,
    asset_type varchar(20) NOT NULL DEFAULT 'IMAGE'
        CHECK (asset_type IN ('IMAGE', 'ATTACHMENT')),
    content_sha256 char(64) CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    description text,
    description_status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (description_status IN ('PENDING', 'REVIEWED', 'NOT_REQUIRED')),
    updated_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT knowledge_asset_version_path_uq UNIQUE (document_version_id, relative_path)
);

CREATE TABLE public.knowledge_chunk (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES public.knowledge_document(id) ON DELETE CASCADE,
    chunk_key varchar(512) NOT NULL,
    section_path text,
    position_no integer NOT NULL CHECK (position_no >= 0),
    active_revision_id uuid,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    CONSTRAINT knowledge_chunk_document_key_uq UNIQUE (document_id, chunk_key),
    CONSTRAINT knowledge_chunk_document_uq UNIQUE (id, document_id)
);
CREATE INDEX knowledge_chunk_document_order_idx
    ON public.knowledge_chunk (document_id, position_no) WHERE status <> 'DELETED';

-- This four-column interface is consumed directly by Spring AI PgVectorStore 1.1.2.
-- Do not add a mandatory business FK: migration-baseline imports also use this table.
CREATE TABLE public.vector_store (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    embedding public.vector(1024) NOT NULL
);
CREATE INDEX spring_ai_vector_index ON public.vector_store
    USING hnsw (embedding public.vector_cosine_ops);
CREATE INDEX vector_store_document_id_idx ON public.vector_store ((metadata ->> 'documentId'));
CREATE INDEX vector_store_source_path_idx ON public.vector_store ((metadata ->> 'sourcePath'));

CREATE TABLE public.knowledge_chunk_revision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    original_content text NOT NULL,
    content text NOT NULL CHECK (length(trim(content)) > 0),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    manually_edited boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'INDEXING', 'READY', 'ACTIVE', 'FAILED', 'SUPERSEDED')),
    vector_record_id uuid UNIQUE REFERENCES public.vector_store(id) ON DELETE SET NULL,
    embedding_model varchar(100),
    embedding_dimensions integer CHECK (embedding_dimensions > 0),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    created_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    indexed_at timestamptz,
    published_at timestamptz,
    error_message text,
    CONSTRAINT knowledge_chunk_revision_no_uq UNIQUE (chunk_id, revision_no),
    CONSTRAINT knowledge_chunk_revision_chunk_uq UNIQUE (id, chunk_id),
    CONSTRAINT knowledge_chunk_revision_chunk_fk FOREIGN KEY (chunk_id, document_id)
        REFERENCES public.knowledge_chunk (id, document_id) ON DELETE CASCADE,
    CONSTRAINT knowledge_chunk_revision_document_version_fk FOREIGN KEY (document_version_id, document_id)
        REFERENCES public.knowledge_document_version (id, document_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX knowledge_chunk_one_active_revision_uq
    ON public.knowledge_chunk_revision (chunk_id) WHERE status = 'ACTIVE';
CREATE INDEX knowledge_chunk_revision_document_version_idx
    ON public.knowledge_chunk_revision (document_version_id);
ALTER TABLE public.knowledge_chunk ADD CONSTRAINT knowledge_chunk_active_revision_fk
    FOREIGN KEY (active_revision_id, id)
    REFERENCES public.knowledge_chunk_revision (id, chunk_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.chat_message_source (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES public.chat_message(id) ON DELETE CASCADE,
    source_no integer NOT NULL CHECK (source_no > 0),
    document_id uuid REFERENCES public.knowledge_document(id) ON DELETE SET NULL,
    document_version_id uuid REFERENCES public.knowledge_document_version(id) ON DELETE SET NULL,
    chunk_revision_id uuid REFERENCES public.knowledge_chunk_revision(id) ON DELETE SET NULL,
    title_snapshot varchar(500) NOT NULL,
    section_snapshot text,
    source_url_snapshot text,
    version_snapshot integer,
    content_snapshot text NOT NULL,
    similarity_score double precision,
    metadata_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata_snapshot) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chat_message_source_order_uq UNIQUE (message_id, source_no)
);
CREATE INDEX chat_message_source_document_idx ON public.chat_message_source (document_id);
CREATE INDEX chat_message_source_document_version_idx ON public.chat_message_source (document_version_id);
CREATE INDEX chat_message_source_chunk_revision_idx ON public.chat_message_source (chunk_revision_id);

CREATE TABLE public.ingest_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type varchar(30) NOT NULL
        CHECK (task_type IN ('IMPORT', 'REIMPORT', 'REINDEX_CHUNK', 'REINDEX_DOCUMENT')),
    document_id uuid REFERENCES public.knowledge_document(id) ON DELETE SET NULL,
    document_version_id uuid REFERENCES public.knowledge_document_version(id) ON DELETE SET NULL,
    chunk_revision_id uuid REFERENCES public.knowledge_chunk_revision(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETE', 'FAILED', 'INTERRUPTED', 'CANCELLED')),
    stage varchar(30) NOT NULL DEFAULT 'QUEUED'
        CHECK (stage IN ('QUEUED', 'STORING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'PUBLISHING', 'FINISHED')),
    total_items integer NOT NULL DEFAULT 0 CHECK (total_items >= 0),
    completed_items integer NOT NULL DEFAULT 0 CHECK (completed_items >= 0),
    attempt_no integer NOT NULL DEFAULT 1 CHECK (attempt_no > 0),
    request_data jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(request_data) = 'object'),
    error_code varchar(100),
    error_message text,
    created_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT ingest_task_progress_valid CHECK (completed_items <= total_items)
);
CREATE INDEX ingest_task_status_recent_idx ON public.ingest_task (status, updated_at DESC);
CREATE INDEX ingest_task_document_idx ON public.ingest_task (document_id, created_at DESC);

COMMENT ON TABLE public.app_user IS 'Future shared-knowledge users; no seed account or password is included.';
COMMENT ON TABLE public.chat_message IS 'Complete message archive; context-window trimming must never delete archive rows.';
COMMENT ON TABLE public.chat_message_source IS 'Source snapshots remain readable after a referenced document is deleted.';
COMMENT ON TABLE public.knowledge_document IS 'Stable document identity; active_version_id changes only after successful indexing.';
COMMENT ON TABLE public.knowledge_chunk IS 'Stable chunk identity; lock_version supports future optimistic concurrency checks.';
COMMENT ON TABLE public.knowledge_chunk_revision IS 'Draft/indexed/manual revisions; publish new text and matching vector together.';
COMMENT ON TABLE public.vector_store IS 'Spring AI PgVectorStore-compatible active retrieval records, 1024-dimensional embeddings.';
COMMENT ON TABLE public.ingest_task IS 'Future durable import/reindex task state; this schema does not implement a worker.';
