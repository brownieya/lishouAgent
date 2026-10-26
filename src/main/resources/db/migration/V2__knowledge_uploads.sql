CREATE TABLE public.knowledge_upload (
    id uuid PRIMARY KEY,
    original_filename varchar(500) NOT NULL,
    file_format varchar(20) NOT NULL CHECK (file_format IN ('MARKDOWN', 'ZIP')),
    original_storage_key uuid NOT NULL,
    created_by uuid REFERENCES public.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.knowledge_upload_file (
    id uuid PRIMARY KEY,
    upload_id uuid NOT NULL REFERENCES public.knowledge_upload(id) ON DELETE CASCADE,
    relative_path varchar(1000) NOT NULL,
    file_type varchar(20) NOT NULL CHECK (file_type IN ('MARKDOWN', 'IMAGE')),
    storage_key uuid NOT NULL UNIQUE,
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT knowledge_upload_file_path_uq UNIQUE (upload_id, relative_path)
);
CREATE INDEX knowledge_upload_file_upload_idx ON public.knowledge_upload_file(upload_id);

COMMENT ON TABLE public.knowledge_upload IS 'Staged Markdown or ZIP upload; does not imply parsing or vector publication.';
COMMENT ON TABLE public.knowledge_upload_file IS 'Validated Markdown and image files with original ZIP-relative paths.';
