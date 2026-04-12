-- File System Files table
-- Stores PDF, converted Markdown, images, and other files imported from local file system
-- Path is the primary key (no tree structure needed,还原时按路径 / 分隔切分构建目录)
CREATE TABLE fs_files (
    path         TEXT PRIMARY KEY,
    is_text      BOOLEAN     NOT NULL DEFAULT FALSE,
    content_bin  BYTEA       NOT NULL,
    content_txt  TEXT,
    mime_type    TEXT,
    file_size    BIGINT,
    created_at   TIMESTAMPTZ DEFAULT now(),
    updated_at   TIMESTAMPTZ DEFAULT now()
);

-- Path prefix search (e.g., list all files under a subdirectory)
CREATE INDEX idx_fs_path_prefix ON fs_files (path text_pattern_ops);
