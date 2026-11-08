-- Allocation Core — SQLite schema, for local runs and tests.
--
-- This is not a production target: SQLite locks the whole database for writes, and a run's evidence is
-- written in one burst. It exists so the schema itself is executable in CI and so a laptop deployment
-- has somewhere to put a run. MySQL and PostgreSQL are the supported production targets.
--
-- Money convention is the same everywhere: *_amount columns are integer counts of 10^-precision units.
--
-- Foreign keys are enforced per connection in SQLite, so a client must issue PRAGMA foreign_keys = ON.

CREATE TABLE IF NOT EXISTS allocation_rule (
  rule_code      TEXT    PRIMARY KEY,
  rule_name      TEXT    NOT NULL,
  schema_version TEXT    NOT NULL,
  precision      INTEGER NOT NULL,
  spec_sha256    TEXT    NOT NULL,
  spec_json      TEXT    NOT NULL,
  status         TEXT    NOT NULL DEFAULT 'ACTIVE',
  create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rule_sha ON allocation_rule (spec_sha256);

-- tenant_id is '' for a single-tenant deployment, which is why the same statements run everywhere: a
-- host without tenants writes the default and the composite unique key degenerates into the key on
-- idempotency_key that it always was. The child tables below carry no tenant column — run_id is a
-- global primary key, so a row can only be reached through a run, and it is the run that is filtered.
CREATE TABLE IF NOT EXISTS allocation_run (
  run_id          TEXT    PRIMARY KEY,
  tenant_id       TEXT    NOT NULL DEFAULT '',
  rule_code       TEXT    NOT NULL,
  rule_sha256     TEXT    NOT NULL,
  idempotency_key TEXT,
  request_sha256  TEXT    NOT NULL,
  status          TEXT    NOT NULL,
  precision       INTEGER NOT NULL,
  input_amount    INTEGER NOT NULL DEFAULT 0,
  output_amount   INTEGER NOT NULL DEFAULT 0,
  pool_count      INTEGER NOT NULL DEFAULT 0,
  row_count       INTEGER NOT NULL DEFAULT 0,
  engine_version  TEXT    NOT NULL,
  failure_code    TEXT,
  failure_message TEXT,
  started_at      INTEGER NOT NULL,
  finished_at     INTEGER,
  create_time     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- Scoped to the tenant: two companies both numbering their monthly batch "2026-01" is normal, and an
  -- index that made the second one fail would be a bug with a plausible-looking error message.
  UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_run_rule_time ON allocation_run (rule_code, create_time);
CREATE INDEX IF NOT EXISTS idx_run_status ON allocation_run (status, create_time);

CREATE TABLE IF NOT EXISTS allocation_pool (
  run_id        TEXT    NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  stage_id      TEXT    NOT NULL,
  pool_key      TEXT    NOT NULL,
  input_amount  INTEGER NOT NULL,
  output_amount INTEGER NOT NULL,
  target_count  INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id, stage_id, pool_key)
);

CREATE TABLE IF NOT EXISTS allocation_line (
  run_id           TEXT    NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  seq              INTEGER NOT NULL,
  stage_id         TEXT    NOT NULL,
  rule_id          TEXT    NOT NULL,
  pool_key         TEXT    NOT NULL,
  target_key       TEXT    NOT NULL,
  target_row_id    TEXT    NOT NULL,
  pool_amount      INTEGER NOT NULL,
  share_amount     INTEGER NOT NULL,
  weight           INTEGER NOT NULL,
  denominator      INTEGER NOT NULL,
  allocated_amount INTEGER NOT NULL,
  PRIMARY KEY (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_line_target ON allocation_line (run_id, target_key);
-- Reading one pool's ledger group is how a result page explains its ranks without reading the run.
CREATE INDEX IF NOT EXISTS idx_line_pool ON allocation_line (run_id, stage_id, rule_id, pool_key);

-- Terminal output rows; see the MySQL variant for why coordinate is one JSON key.
CREATE TABLE IF NOT EXISTS allocation_result (
  run_id     TEXT    NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  seq        INTEGER NOT NULL,
  stage_id   TEXT    NOT NULL,
  row_id     TEXT    NOT NULL,
  coordinate TEXT    NOT NULL,
  amount     INTEGER NOT NULL,
  PRIMARY KEY (run_id, seq)
);

CREATE TABLE IF NOT EXISTS allocation_reconcile (
  run_id      TEXT    NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  ref_sha256  TEXT    NOT NULL,
  status      TEXT    NOT NULL,
  matched     INTEGER NOT NULL DEFAULT 0,
  differed    INTEGER NOT NULL DEFAULT 0,
  missing     INTEGER NOT NULL DEFAULT 0,
  extra       INTEGER NOT NULL DEFAULT 0,
  report_path TEXT,
  create_time TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, ref_sha256)
);
