CREATE TABLE IF NOT EXISTS captures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    har_json TEXT NOT NULL,
    request_count INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS workflows (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    capture_id_1 INTEGER NOT NULL,
    capture_id_2 INTEGER NOT NULL,
    workflow_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workflow_id INTEGER NOT NULL,
    inputs_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    success INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
