package cl.duoc.eft.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    public static final String UNIQUE_ROWS = """
        WITH ranked AS (
          SELECT r.*, ROW_NUMBER() OVER(PARTITION BY business_key ORDER BY source_file,line_number) AS rn
          FROM legacy_row r WHERE dataset=? AND kind=? AND status='ACCEPTED'
        )
        """;
    private final JdbcTemplate jdbc;
    public ReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void export(String dataset, Kind kind, Path output) throws IOException {
        Path folder = output.resolve(kind.name().toLowerCase()).resolve(dataset);
        Files.createDirectories(folder);
        csv(folder.resolve("control.csv"), List.of("source_file","leidos","rechazados","unicos","duplicados"), """
            SELECT source_file,COUNT(*) AS leidos,
              SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END) AS rechazados,
              SUM(CASE WHEN status='ACCEPTED' AND rn=1 THEN 1 ELSE 0 END) AS unicos,
              SUM(CASE WHEN status='ACCEPTED' AND rn>1 THEN 1 ELSE 0 END) AS duplicados
            FROM (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY business_key ORDER BY source_file,line_number) AS rn
                  FROM legacy_row r WHERE dataset=? AND kind=?) counts
            GROUP BY source_file ORDER BY source_file
            """, dataset, kind.name());
        csv(folder.resolve("rechazos.csv"), List.of("source_file","line_number","reason"),
                "SELECT source_file,line_number,reason FROM legacy_row WHERE dataset=? AND kind=? AND status='REJECTED' ORDER BY source_file,line_number",
                dataset, kind.name());
        csv(folder.resolve("duplicados.csv"), List.of("source_file","line_number","business_key"),
                UNIQUE_ROWS + "SELECT source_file,line_number,business_key FROM ranked WHERE rn>1 ORDER BY source_file,line_number", dataset, kind.name());

        List<String> columns = switch (kind) {
            case DAILY -> List.of("legacy_id","event_date","transaction_type","amount","source_file","line_number");
            case INTEREST -> List.of("account_id","holder_name","age","transaction_type","amount","rate","period_label","interest","final_balance","source_file","line_number");
            case ANNUAL -> List.of("account_id","event_date","transaction_type","amount","description","source_file","line_number");
        };
        csv(folder.resolve("procesados.csv"), columns, UNIQUE_ROWS +
                "SELECT " + String.join(",", columns) + " FROM ranked WHERE rn=1 ORDER BY source_file,line_number", dataset, kind.name());
        if (kind == Kind.DAILY) {
            csv(folder.resolve("resumen.csv"), List.of("event_date","transaction_type","cantidad","total"), UNIQUE_ROWS + """
                SELECT event_date,transaction_type,COUNT(*) AS cantidad,SUM(amount) AS total
                FROM ranked WHERE rn=1 GROUP BY event_date,transaction_type ORDER BY event_date,transaction_type
                """, dataset, kind.name());
        } else if (kind == Kind.ANNUAL) {
            csv(folder.resolve("resumen.csv"), List.of("account_id","anio","cantidad","abonos","cargos","neto"), UNIQUE_ROWS + """
                SELECT account_id,EXTRACT(YEAR FROM event_date) AS anio,COUNT(*) AS cantidad,
                SUM(CASE WHEN amount>0 THEN amount ELSE 0 END) AS abonos,
                SUM(CASE WHEN amount<0 THEN -amount ELSE 0 END) AS cargos,SUM(amount) AS neto
                FROM ranked WHERE rn=1 GROUP BY account_id,EXTRACT(YEAR FROM event_date) ORDER BY account_id,anio
                """, dataset, kind.name());
        }
    }

    private void csv(Path target, List<String> columns, String query, Object... parameters) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "report-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(String.join(",", columns));
                writer.newLine();
                jdbc.query(connection -> {
                    var statement = connection.prepareStatement(query);
                    statement.setFetchSize(500);
                    for (int i=0; i<parameters.length; i++) statement.setObject(i+1, parameters[i]);
                    return statement;
                }, (ResultSetExtractor<Void>) result -> {
                    try {
                        while (result.next()) {
                            for (int i=0; i<columns.size(); i++) {
                                if (i>0) writer.write(',');
                                writer.write(escaped(result.getObject(columns.get(i))));
                            }
                            writer.newLine();
                        }
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                    return null;
                });
            } catch (UncheckedIOException ex) { throw ex.getCause(); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String escaped(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) return "\"" + text.replace("\"", "\"\"") + "\"";
        return text;
    }
}
