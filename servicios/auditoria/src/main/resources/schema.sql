CREATE TABLE IF NOT EXISTS audit_event (
 event_id VARCHAR(36) PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL,
 received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS audit_delivery (
 broker_topic VARCHAR(100) NOT NULL,
 broker_partition INTEGER NOT NULL,
 broker_offset BIGINT NOT NULL,
 event_id VARCHAR(36) NOT NULL REFERENCES audit_event(event_id),
 PRIMARY KEY(broker_topic,broker_partition,broker_offset)
);
CREATE INDEX IF NOT EXISTS idx_audit_delivery_event ON audit_delivery(event_id);
