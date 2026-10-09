CREATE TABLE IF NOT EXISTS legacy_row (
  record_key VARCHAR(64) PRIMARY KEY,
  dataset VARCHAR(64) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  source_file VARCHAR(255) NOT NULL,
  line_number INTEGER NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACCEPTED','REJECTED')),
  reason VARCHAR(255),
  business_key VARCHAR(64),
  legacy_id BIGINT,
  account_id BIGINT,
  event_date DATE,
  transaction_type VARCHAR(30),
  amount DECIMAL(19,2),
  holder_name VARCHAR(255),
  age INTEGER,
  description VARCHAR(1000),
  rate DECIMAL(11,8),
  interest DECIMAL(19,2),
  final_balance DECIMAL(19,2),
  period_label VARCHAR(40)
);
CREATE INDEX IF NOT EXISTS idx_legacy_dataset_kind ON legacy_row(dataset, kind, status);
CREATE INDEX IF NOT EXISTS idx_legacy_business ON legacy_row(dataset, kind, business_key);
