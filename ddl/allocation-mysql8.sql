-- Allocation Core — MySQL 8 schema.
--
-- Money convention: every *_amount column is an integer count of 10^-precision units, where precision
-- comes from the rule (0..6). A value of 123456 at precision 2 means 1234.56. Storing scaled integers
-- rather than DECIMAL is deliberate: the engine's arithmetic is exact in that domain, and a DECIMAL
-- column invites a re-scaling step that could round money while moving it.
--
-- The run table is the audit spine. Everything else hangs off run_id and is immutable once the run is
-- PUBLISHED.

CREATE TABLE IF NOT EXISTS allocation_rule (
  rule_code      VARCHAR(64)  NOT NULL,
  rule_name      VARCHAR(128) NOT NULL,
  schema_version VARCHAR(32)  NOT NULL,
  precision      TINYINT      NOT NULL,
  spec_sha256    CHAR(64)     NOT NULL,
  spec_json      JSON         NOT NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (rule_code),
  KEY idx_rule_sha (spec_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS allocation_run (
  run_id          VARCHAR(32)  NOT NULL,
  rule_code       VARCHAR(64)  NOT NULL,
  rule_sha256     CHAR(64)     NOT NULL,
  idempotency_key VARCHAR(128)     NULL,
  request_sha256  CHAR(64)     NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  precision       TINYINT      NOT NULL,
  input_amount    BIGINT       NOT NULL DEFAULT 0,
  output_amount   BIGINT       NOT NULL DEFAULT 0,
  pool_count      INT          NOT NULL DEFAULT 0,
  row_count       BIGINT       NOT NULL DEFAULT 0,
  engine_version  VARCHAR(32)  NOT NULL,
  failure_code    VARCHAR(16)      NULL,
  failure_message VARCHAR(512)     NULL,
  started_at      DATETIME(3)  NOT NULL,
  finished_at     DATETIME(3)      NULL,
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_idem (idempotency_key),
  KEY idx_rule_time (rule_code, create_time),
  KEY idx_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS allocation_pool (
  run_id        VARCHAR(32) NOT NULL,
  stage_id      VARCHAR(64) NOT NULL,
  pool_key      VARCHAR(1024) NOT NULL,
  input_amount  BIGINT      NOT NULL,
  output_amount BIGINT      NOT NULL,
  target_count  INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (run_id, stage_id, pool_key),
  CONSTRAINT fk_pool_run FOREIGN KEY (run_id) REFERENCES allocation_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS allocation_line (
  run_id           VARCHAR(32)  NOT NULL,
  seq              BIGINT       NOT NULL,
  stage_id         VARCHAR(64)  NOT NULL,
  rule_id          VARCHAR(64)  NOT NULL,
  pool_key         VARCHAR(1024) NOT NULL,
  target_key       VARCHAR(1024) NOT NULL,
  target_row_id    VARCHAR(128)  NOT NULL,
  pool_amount      BIGINT       NOT NULL,
  share_amount     BIGINT       NOT NULL,
  weight           BIGINT       NOT NULL,
  denominator      BIGINT       NOT NULL,
  allocated_amount BIGINT       NOT NULL,
  PRIMARY KEY (run_id, seq),
  KEY idx_line_target (run_id, target_key(255)),
  CONSTRAINT fk_line_run FOREIGN KEY (run_id) REFERENCES allocation_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS allocation_reconcile (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  run_id      VARCHAR(32)  NOT NULL,
  ref_sha256  CHAR(64)     NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  matched     BIGINT       NOT NULL DEFAULT 0,
  differed    BIGINT       NOT NULL DEFAULT 0,
  missing     BIGINT       NOT NULL DEFAULT 0,
  extra       BIGINT       NOT NULL DEFAULT 0,
  report_path VARCHAR(512)     NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_reconcile_run (run_id),
  CONSTRAINT fk_reconcile_run FOREIGN KEY (run_id) REFERENCES allocation_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
