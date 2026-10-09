CREATE TABLE IF NOT EXISTS payment_request (
 id VARCHAR(36) PRIMARY KEY,
 fingerprint VARCHAR(64) NOT NULL,
 caller VARCHAR(150) NOT NULL,
 request_json TEXT NOT NULL,
 status VARCHAR(12) NOT NULL CHECK(status IN ('PENDIENTE','COMPLETADO','RECHAZADO')),
 receipt TEXT,
 reason VARCHAR(255),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS payment_outbox (
 event_id VARCHAR(36) PRIMARY KEY REFERENCES payment_request(id),
 event_type VARCHAR(50) NOT NULL,
 payload TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_payment_outbox_publish ON payment_outbox(published_at,created_at,event_id);
