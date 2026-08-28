CREATE TABLE fs_import_batches (
    import_id           UUID PRIMARY KEY,
    source_type         VARCHAR(32)  NOT NULL,
    original_filename   VARCHAR(512) NOT NULL,
    display_name        VARCHAR(512) NOT NULL,
    entry_path          TEXT         NOT NULL UNIQUE
        REFERENCES fs_files(path) ON DELETE CASCADE,
    original_path       TEXT         NOT NULL UNIQUE,
    file_count          INTEGER      NOT NULL CHECK (file_count >= 1),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_fs_import_batches_source_type
        CHECK (source_type IN ('PDF'))
);

CREATE INDEX idx_fs_import_batches_created_at
    ON fs_import_batches (created_at DESC, import_id);
