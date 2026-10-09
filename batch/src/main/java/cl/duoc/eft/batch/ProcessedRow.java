package cl.duoc.eft.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProcessedRow(SourceRow source, String status, String reason, String businessKey,
                           Long legacyId, Long accountId, LocalDate date, String type, BigDecimal amount,
                           String name, Integer age, String description, BigDecimal rate,
                           BigDecimal interest, BigDecimal finalBalance, String period) {
    public static ProcessedRow rejected(SourceRow source, String reason) {
        return new ProcessedRow(source, "REJECTED", reason, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
