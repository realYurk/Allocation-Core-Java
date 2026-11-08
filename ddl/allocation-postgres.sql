-- Allocation Core — PostgreSQL 15+ schema.
--
-- Money convention: every *_amount column is an integer count of 10^-precision units, where precision
-- comes from the rule (0..6). A value of 123456 at precision 2 means 1234.56. BIGINT is used rather than
-- NUMERIC so no implicit re-scaling can round money while moving it.


-- tenant_id is '' for a single-tenant deployment, so the same statements run everywhere and the
-- composite unique key degenerates into the key on idempotency_key that it always was. The child tables
-- carry no tenant column: run_id is a global primary key, so a child row can only be reached through a
-- run, and it is the run that is filtered.
CREATE TABLE IF NOT EXISTS allocation_run (
  run_id          VARCHAR(32)  PRIMARY KEY,
  tenant_id       VARCHAR(64)  NOT NULL DEFAULT '',
  rule_code       VARCHAR(64)  NOT NULL,
  rule_sha256     CHAR(64)     NOT NULL,
  -- The rule this run executed, byte for byte. TEXT and not a JSON type on purpose: MySQL's JSON and
  -- PostgreSQL's jsonb both normalise what you put in (key order, whitespace, number formatting), and the
  -- digest recorded above is a digest of the exact bytes. A database that reformats the document would
  -- make every run fail its own integrity check.
  rule_json       TEXT,
  idempotency_key VARCHAR(128),
  request_sha256  CHAR(64)     NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  scale           SMALLINT     NOT NULL,
  input_amount    BIGINT       NOT NULL DEFAULT 0,
  output_amount   BIGINT       NOT NULL DEFAULT 0,
  pool_count      INTEGER      NOT NULL DEFAULT 0,
  row_count       BIGINT       NOT NULL DEFAULT 0,
  engine_version  VARCHAR(32)  NOT NULL,
  failure_code    VARCHAR(16),
  failure_message VARCHAR(512),
  started_at      BIGINT       NOT NULL,
  finished_at     BIGINT,
  create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  -- Scoped to the tenant: two companies both numbering their monthly batch "2026-01" is normal, and an
  -- index that made the second one fail would be a bug with a plausible-looking error message.
  CONSTRAINT uk_idem UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_run_rule_time ON allocation_run (rule_code, create_time);
CREATE INDEX IF NOT EXISTS idx_run_status ON allocation_run (status, create_time);

-- A pool's balance proof, one row per pool per stage. The primary key is (run_id, seq) rather than
-- (run_id, stage_id, pool_key) for the same reason allocation_line and allocation_result use a seq:
-- pool_key is an arbitrary-length coordinate key, and a coordinate column must not be part of a primary
-- key (which is what forced MySQL's otherwise-pointless VARCHAR(1024) cap).
CREATE TABLE IF NOT EXISTS allocation_pool (
  run_id        VARCHAR(32)   NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  seq           BIGINT        NOT NULL,
  stage_id      VARCHAR(64)   NOT NULL,
  pool_key      TEXT          NOT NULL,
  input_amount  BIGINT        NOT NULL,
  output_amount BIGINT        NOT NULL,
  target_count  INTEGER       NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id, seq)
);

CREATE TABLE IF NOT EXISTS allocation_line (
  run_id           VARCHAR(32)  NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  seq              BIGINT       NOT NULL,
  stage_id         VARCHAR(64)  NOT NULL,
  rule_id          VARCHAR(64)  NOT NULL,
  pool_key         TEXT         NOT NULL,
  target_key       TEXT         NOT NULL,
  target_row_id    VARCHAR(128) NOT NULL,
  pool_amount      BIGINT       NOT NULL,
  share_amount     BIGINT       NOT NULL,
  weight           BIGINT       NOT NULL,
  denominator      BIGINT       NOT NULL,
  allocated_amount BIGINT       NOT NULL,
  PRIMARY KEY (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_line_target ON allocation_line (run_id, target_key);
-- Reading one pool's ledger group is how a result page explains its ranks without reading the run.
CREATE INDEX IF NOT EXISTS idx_line_pool ON allocation_line (run_id, stage_id, rule_id, pool_key);

-- Terminal output rows; see the MySQL variant for why coordinate is one JSON key.
CREATE TABLE IF NOT EXISTS allocation_result (
  run_id     VARCHAR(32)  NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  seq        BIGINT       NOT NULL,
  stage_id   VARCHAR(64)  NOT NULL,
  row_id     VARCHAR(128) NOT NULL,
  coordinate TEXT         NOT NULL,
  amount     BIGINT       NOT NULL,
  PRIMARY KEY (run_id, seq)
);
