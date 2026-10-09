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
CREATE TABLE IF NOT EXISTS audit_delivery_v2 (
 broker_source VARCHAR(100) NOT NULL,
 broker_topic VARCHAR(100) NOT NULL,
 broker_partition INTEGER NOT NULL,
 broker_offset BIGINT NOT NULL,
 event_id VARCHAR(36) NOT NULL REFERENCES audit_event(event_id),
 PRIMARY KEY(broker_source,broker_topic,broker_partition,broker_offset)
);
CREATE INDEX IF NOT EXISTS idx_audit_delivery_v2_event ON audit_delivery_v2(event_id);
INSERT INTO audit_delivery_v2(broker_source,broker_topic,broker_partition,broker_offset,event_id)
 SELECT 'local', d.broker_topic,d.broker_partition,d.broker_offset,d.event_id FROM audit_delivery d
 WHERE NOT EXISTS (SELECT 1 FROM audit_delivery_v2 v WHERE v.broker_source='local'
 AND v.broker_topic=d.broker_topic AND v.broker_partition=d.broker_partition AND v.broker_offset=d.broker_offset);
