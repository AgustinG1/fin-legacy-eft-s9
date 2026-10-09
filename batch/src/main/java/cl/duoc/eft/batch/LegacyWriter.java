package cl.duoc.eft.batch;

import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LegacyWriter implements ItemWriter<ProcessedRow> {
    private final JdbcTemplate jdbc;
    public LegacyWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void write(Chunk<? extends ProcessedRow> chunk) {
        List<Object[]> keys = new ArrayList<>();
        List<Object[]> values = new ArrayList<>();
        for (ProcessedRow row : chunk) {
            SourceRow s = row.source();
            String key = InputManifest.hash(s.dataset() + "|" + s.kind() + "|" + s.source() + "|" + s.line());
            keys.add(new Object[]{key});
            values.add(new Object[]{key, s.dataset(), s.kind().name(), s.source(), s.line(), row.status(),
                    row.reason(), row.businessKey(), row.legacyId(), row.accountId(), row.date(), row.type(),
                    row.amount(), row.name(), row.age(), row.description(), row.rate(), row.interest(),
                    row.finalBalance(), row.period()});
        }
        // Each partition owns different source rows. Delete + insert shares the chunk transaction:
        // a failed write rolls back both, and a replay cannot append the same source row twice.
        jdbc.batchUpdate("DELETE FROM legacy_row WHERE record_key = ?", keys);
        jdbc.batchUpdate("""
                INSERT INTO legacy_row(record_key,dataset,kind,source_file,line_number,status,reason,business_key,
                legacy_id,account_id,event_date,transaction_type,amount,holder_name,age,description,rate,interest,
                final_balance,period_label) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, values);
    }
}
