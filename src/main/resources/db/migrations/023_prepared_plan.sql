-- 底座审批方案：审批状态独立于 payload 中的执行状态，方案内容和指纹不可原地修改。
CREATE TABLE IF NOT EXISTS platform_prepared_plan (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    payload_text MEDIUMTEXT NOT NULL,
    KEY idx_platform_prepared_plan_due (next_attempt_at, lease_until),
    KEY idx_platform_prepared_plan_owner (owner_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
