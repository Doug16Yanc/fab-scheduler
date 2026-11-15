CREATE TABLE IF NOT EXISTS sim_run (
    id                    BIGSERIAL PRIMARY KEY,
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    rule                  TEXT             NOT NULL,
    seed                  BIGINT           NOT NULL,
    horizon_days          DOUBLE PRECISION NOT NULL,
    released              INT              NOT NULL,
    completed             INT              NOT NULL,
    avg_cycle_hours       DOUBLE PRECISION NOT NULL,
    avg_tardiness_hours   DOUBLE PRECISION NOT NULL,
    tardy_percent         DOUBLE PRECISION NOT NULL,
    queue_time_violations INT              NOT NULL,
    throughput_per_day    DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS sim_operation (
    run_id     BIGINT           NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    machine_id TEXT             NOT NULL,
    tool_group TEXT             NOT NULL,
    start_min  DOUBLE PRECISION NOT NULL,
    end_min    DOUBLE PRECISION NOT NULL,
    lot_ids    INTEGER[]        NOT NULL,
    hot        BOOLEAN          NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sim_operation_run
    ON sim_operation (run_id, machine_id, start_min);
