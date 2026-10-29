CREATE TABLE IF NOT EXISTS members (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL
);
INSERT OR IGNORE INTO members VALUES ('me', '나'), ('sister', '누나'), ('brother-in-law', '매형'), ('mom', '엄마'), ('dad', '아빠');
CREATE TABLE IF NOT EXISTS invites (
    code_hash TEXT PRIMARY KEY,
    member_id TEXT NOT NULL UNIQUE REFERENCES members(id),
    expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    member_id TEXT NOT NULL REFERENCES members(id),
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
CREATE TABLE IF NOT EXISTS posts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL UNIQUE,
    member_id TEXT NOT NULL REFERENCES members(id),
    caption TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS photos (
    id TEXT PRIMARY KEY,
    post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    mime_type TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS photos_post ON photos(post_id, position);
CREATE TABLE IF NOT EXISTS likes (
    post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    member_id TEXT NOT NULL REFERENCES members(id),
    PRIMARY KEY (post_id, member_id)
);
