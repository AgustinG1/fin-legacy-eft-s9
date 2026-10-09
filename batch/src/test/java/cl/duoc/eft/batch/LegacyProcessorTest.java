package cl.duoc.eft.batch;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LegacyProcessorTest {
    private ProcessedRow process(Kind kind, String raw) {
        return new LegacyProcessor(kind, "0.05", "TRIMESTRAL-DEMO")
                .process(new SourceRow("fixture", kind, "semana_1/" + kind.filename, 2, raw));
    }

    @Test void annualWithdrawalsPreserveNegativeSignAndDoNotBecomeDeposits() {
        var row = process(Kind.ANNUAL, "101,2024-03-15,retiro,-500,Retiro parcial");
        assertThat(row.status()).isEqualTo("ACCEPTED");
        assertThat(row.amount()).isEqualByComparingTo("-500.00");
        assertThat(process(Kind.ANNUAL, "101,2024-03-15,retiro,500,Retiro parcial").status()).isEqualTo("REJECTED");
    }

    @Test void acceptsDocumentedDateVariantsButRejectsImpossibleDates() {
        assertThat(process(Kind.DAILY, "1,2024/02/29,20,credito").date()).hasToString("2024-02-29");
        assertThat(process(Kind.DAILY, "1,2023-02-29,20,credito").reason()).isEqualTo("FECHA_INVALIDA");
    }

    @Test void dailyAnomaliesAreAuditedInsteadOfContributingToTotals() {
        assertThat(process(Kind.DAILY, "1,2024-01-01,-20,debito").reason()).isEqualTo("MONTO_NO_POSITIVO");
        assertThat(process(Kind.DAILY, "1,2024-01-01,,credito").reason()).isEqualTo("MONTO_VACIO_O_INVALIDO");
        assertThat(process(Kind.DAILY, "1,2024-01-01,20,otro").reason()).isEqualTo("TIPO_MOVIMIENTO_INVALIDO");
    }

    @Test void configurableInterestCalculatesFromInputNotFromPreviouslyCalculatedBalance() {
        var row = process(Kind.INTEREST, "101,John Doe,5000,30,ahorro");
        assertThat(row.interest()).isEqualByComparingTo("250.00");
        assertThat(row.finalBalance()).isEqualByComparingTo("5250.00");
        assertThat(row.period()).isEqualTo("TRIMESTRAL-DEMO");
        var other = new LegacyProcessor(Kind.INTEREST,"0.01","MENSUAL-PRUEBA")
                .process(new SourceRow("fixture", Kind.INTEREST,"file",2,"101,John Doe,5000,30,ahorro"));
        assertThat(other.interest()).isEqualByComparingTo("50.00");
    }

    @Test void refusesToInventInterestRate() {
        assertThatThrownBy(() -> new LegacyProcessor(Kind.INTEREST, "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eft.interest-rate");
    }

    @Test void duplicateKeyNormalizesRepresentationButKeepsDistinctTransactionIds() {
        var a = process(Kind.DAILY,"1,2024-01-01,20,credito");
        var b = process(Kind.DAILY,"1,2024/01/01,20.00, CREDITO ");
        var c = process(Kind.DAILY,"2,2024-01-01,20,credito");
        assertThat(a.businessKey()).isEqualTo(b.businessKey()).isNotEqualTo(c.businessKey());
    }

    @Test void rejectsMalformedColumnsAndExcessPrecision() {
        assertThat(process(Kind.DAILY,"1,2024-01-01,20,credito,extra").status()).isEqualTo("REJECTED");
        assertThat(process(Kind.DAILY,"1,2024-01-01,20.005,credito").reason()).isEqualTo("MAS_DE_DOS_DECIMALES");
    }

    @Test void acceptsMortgageDataAndRejectsInvalidAgeAndEmptyDescription() {
        assertThat(process(Kind.INTEREST,"105,Charlie Green,7000,35,hipoteca").status()).isEqualTo("ACCEPTED");
        assertThat(process(Kind.INTEREST,"105,Charlie Green,7000,200,hipoteca").reason()).isEqualTo("EDAD_FUERA_DE_RANGO");
        assertThat(process(Kind.ANNUAL,"1,2024-01-01,deposito,20,").reason()).isEqualTo("DESCRIPCION_INVALIDA");
    }
}
