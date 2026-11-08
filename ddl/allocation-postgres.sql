-- Allocation Core — PostgreSQL 15+ schema.
--
-- Money convention: every *_amount column is an integer count of 10^-precision units, where precision
-- comes from the rule (0..6). A value of 123456 at precision 2 means 1234.56. BIGINT is used rather than
-- NUMERIC so no implicit re-scaling can round money while moving it.

CREATE TABLE IF NOT EXISTS allocation_rule (
  rule_code      VARCHAR(64)  PRIMARY KEY,
  rule_name      VARCHAR(128) NOT NULL,
  schema_version VARCHAR(32)  NOT NULL,
  precision      SMALLINT     NOT NULL CHECK (precision BETWEEN 0 AND 6),
  spec_sha256    CHAR(64)     NOT NULL,
  spec_json      JSONB        NOT NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  create_time    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rule_sha ON allocation_rule (spec_sha256);

CREATE TABLE IF NOT EXISTS allocation_run (
  run_id          VARCHAR(32)  PRIMARY KEY,
  rule_code       VARCHAR(64)  NOT NULL,
  rule_sha256     CHAR(64)     NOT NULL,
  idempotency_key VARCHAR(128) UNIQUE,
  request_sha256  CHAR(64)     NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  precision       SMALLINT     NOT NULL,
  input_amount    BIGINT       NOT NULL DEFAULT 0,
  output_amount   BIGINT       NOT NULL DEFAULT 0,
  pool_count      INTEGER      NOT NULL DEFAULT 0,
  row_count       BIGINT       NOT NULL DEFAULT 0,
  engine_version  VARCHAR(32)  NOT NULL,
  failure_code    VARCHAR(16),
  failure_message VARCHAR(512),
  started_at      BIGINT       NOT NULL,
  finished_at     BIGINT,
  create_time     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_run_rule_time ON allocation_run (rule_code, create_time);
CREATE INDEX IF NOT EXISTS idx_run_status ON allocation_run (status, create_time);

CREATE TABLE IF NOT EXISTS allocation_pool (
  run_id        VARCHAR(32)   NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  stage_id      VARCHAR(64)   NOT NULL,
  pool_key      TEXT          NOT NULL,
  input_amount  BIGINT        NOT NULL,
  output_amount BIGINT        NOT NULL,
  target_count  INTEGER       NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id, stage_id, pool_key)
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

CREATE TABLE IF NOT EXISTS allocation_reconcile (
  run_id      VARCHAR(32) NOT NULL REFERENCES allocation_run (run_id) ON DELETE CASCADE,
  ref_sha256  CHAR(64)    NOT NULL,
  status      VARCHAR(16) NOT NULL,
  matched     BIGINT      NOT NULL DEFAULT 0,
  differed    BIGINT      NOT NULL DEFAULT 0,
  missing     BIGINT      NOT NULL DEFAULT 0,
  extra       BIGINT      NOT NULL DEFAULT 0,
  report_path VARCHAR(512),
  create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, ref_sha256)
);
