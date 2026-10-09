package cl.duoc.eft.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;

/** Pure transformation: no in-memory duplicate state survives a rolled-back chunk. */
public class LegacyProcessor implements ItemProcessor<SourceRow, ProcessedRow> {
    private final BigDecimal rate;
    private final String period;
    private final DelimitedLineTokenizer tokenizer;

    public LegacyProcessor(Kind kind, String rate, String period) {
        this.tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames(kind.header.split(","));
        this.rate = kind == Kind.INTEREST ? checkedRate(rate) : null;
        this.period = kind == Kind.INTEREST ? required(period, "PERIODO_REQUERIDO", 40) : null;
    }

    public static BigDecimal checkedRate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Indique eft.interest-rate o use el perfil demo");
        BigDecimal result = new BigDecimal(value).setScale(8, RoundingMode.UNNECESSARY);
        if (result.signum() < 0 || result.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Tasa fuera de 0..1");
        return result;
    }

    @Override
    public ProcessedRow process(SourceRow row) {
        try {
            String[] v = tokenizer.tokenize(row.raw()).getValues();
            return switch (row.kind()) {
                case DAILY -> daily(row, v);
                case INTEREST -> interest(row, v);
                case ANNUAL -> annual(row, v);
            };
        } catch (IllegalArgumentException | ArithmeticException ex) {
            String reason = ex.getMessage() == null ? "VALOR_INVALIDO" : ex.getMessage();
            return ProcessedRow.rejected(row, reason.length() > 255 ? reason.substring(0, 255) : reason);
        } catch (org.springframework.batch.item.file.transform.FlatFileFormatException ex) {
            return ProcessedRow.rejected(row, "COLUMNAS_INVALIDAS");
        }
    }

    private ProcessedRow daily(SourceRow row, String[] v) {
        long id = positiveId(v[0]);
        LocalDate date = date(v[1]);
        BigDecimal amount = money(v[2]);
        check(amount.signum() > 0, "MONTO_NO_POSITIVO");
        String type = normalize(v[3]);
        check(Set.of("debito", "credito").contains(type), "TIPO_MOVIMIENTO_INVALIDO");
        return new ProcessedRow(row, "ACCEPTED", null, key(id, date, amount, type), id, null, date,
                type, amount, null, null, null, null, null, null, null);
    }

    private ProcessedRow interest(SourceRow row, String[] v) {
        long accountId = positiveId(v[0]);
        String name = required(v[1], "NOMBRE_INVALIDO", 255);
        BigDecimal balance = money(v[2]);
        check(balance.signum() >= 0, "SALDO_NEGATIVO");
        int age;
        try { age = Integer.parseInt(v[3].trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("EDAD_INVALIDA"); }
        check(age >= 18 && age <= 100, "EDAD_FUERA_DE_RANGO");
        String type = normalize(v[4]);
        check(Set.of("ahorro", "prestamo", "hipoteca").contains(type), "TIPO_CUENTA_INVALIDO");
        BigDecimal interest = balance.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalBalance = balance.add(interest);
        check(finalBalance.precision() <= 19, "SALDO_RESULTANTE_FUERA_DE_RANGO");
        return new ProcessedRow(row, "ACCEPTED", null,
                key(accountId, normalize(name), balance, age, type, rate, period), null, accountId,
                null, type, balance, name, age, null, rate, interest, finalBalance, period);
    }

    private ProcessedRow annual(SourceRow row, String[] v) {
        long accountId = positiveId(v[0]);
        LocalDate date = date(v[1]);
        String type = normalize(v[2]);
        check(Set.of("deposito", "retiro", "compra", "pago").contains(type), "TIPO_OPERACION_INVALIDO");
        BigDecimal amount = money(v[3]);
        check(type.equals("deposito") ? amount.signum() > 0 : amount.signum() < 0, "SIGNO_MONTO_INVALIDO");
        String description = required(v[4], "DESCRIPCION_INVALIDA", 1000);
        return new ProcessedRow(row, "ACCEPTED", null, key(accountId, date, type, amount, normalize(description)),
                null, accountId, date, type, amount, null, null, description, null, null, null, null);
    }

    private static BigDecimal money(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.trim()).setScale(2, RoundingMode.UNNECESSARY);
            check(amount.precision() <= 19, "MONTO_FUERA_DE_RANGO");
            return amount;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("MONTO_VACIO_O_INVALIDO"); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("MAS_DE_DOS_DECIMALES"); }
    }

    private static long positiveId(String value) {
        try {
            long id = Long.parseLong(value.trim());
            check(id > 0, "IDENTIFICADOR_NO_POSITIVO");
            return id;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("IDENTIFICADOR_INVALIDO"); }
    }

    private static LocalDate date(String value) {
        for (String pattern : new String[]{"uuuu-MM-dd", "uuuu/MM/dd", "dd-MM-uuuu", "dd/MM/uuuu"}) {
            try { return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)); }
            catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("FECHA_INVALIDA");
    }

    private static String required(String value, String reason, int max) {
        check(value != null && !value.isBlank() && value.trim().length() <= max, reason);
        return value.trim();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String key(Object... fields) {
        return InputManifest.hash(Arrays.stream(fields).map(Object::toString)
                .map(s -> s.length() + ":" + s).collect(Collectors.joining()));
    }

    private static void check(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
