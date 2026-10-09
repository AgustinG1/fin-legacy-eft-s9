package cl.duoc.eft.services.shared;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Models {
    private Models() { }
    public record NuevoCliente(@NotNull UUID id, @NotBlank @Size(max=64) String documento,
            @NotBlank @Size(max=120) String nombre, @NotBlank @Email @Size(max=200) String email) { }
    public record EditarCliente(@NotBlank @Size(max=120) String nombre,
            @NotBlank @Email @Size(max=200) String email, @NotNull @PositiveOrZero Long version) { }
    public record Cliente(UUID id, String documento, String nombre, String email, long version) { }
    public record AbrirCuenta(@NotNull UUID id, @NotNull UUID clienteId,
            @NotNull TipoCuenta tipo, @NotBlank @Size(max=80) String alias) { }
    public enum TipoCuenta { AHORRO, CORRIENTE }
    public record EditarCuenta(@NotBlank @Size(max=80) String alias, @NotNull @PositiveOrZero Long version) { }
    public record Cuenta(UUID id, UUID clienteId, TipoCuenta tipo, String alias,
                         String moneda, String estado, BigDecimal saldo, long version) { }
    public enum TipoPago { DEPOSITO, RETIRO, TRANSFERENCIA, PAGO }
    public record OrdenPago(@NotNull UUID id, @NotNull TipoPago tipo, UUID origen, UUID destino,
            @NotNull @DecimalMin("0.01") @Digits(integer=15,fraction=2) BigDecimal monto,
            @NotBlank @Size(max=200) String concepto) { }
    public record Comprobante(UUID id, TipoPago tipo, UUID origen, UUID destino, BigDecimal monto,
            BigDecimal saldoOrigen, BigDecimal saldoDestino, Instant fecha) { }
    public record Pago(UUID id, String estado, Comprobante comprobante, String motivo) { }
}
