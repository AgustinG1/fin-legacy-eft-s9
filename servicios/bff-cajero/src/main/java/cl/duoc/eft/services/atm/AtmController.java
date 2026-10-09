package cl.duoc.eft.services.atm;
import cl.duoc.eft.services.channels.ChannelService;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/cajero")
public class AtmController {
    private final ChannelService channels;
    public AtmController(ChannelService channels) { this.channels=channels; }
    public record Saldo(UUID cuenta,BigDecimal saldo,String moneda) {}
    public record Retiro(@NotNull UUID id,@NotNull @DecimalMin("1") @Digits(integer=15,fraction=0) BigDecimal monto) {}
    @GetMapping("/cuentas/{id}/saldo") @PreAuthorize("hasAuthority('SCOPE_cajero.read')")
    public Saldo balance(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        Cuenta account=channels.ownAccount(jwt,id); return new Saldo(account.id(),account.saldo(),account.moneda());
    }
    @PostMapping("/cuentas/{account}/retiros") @PreAuthorize("hasAuthority('SCOPE_cajero.write')")
    public Pago withdraw(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID account,@Valid @RequestBody Retiro request) {
        return channels.withdraw(jwt,account,request.id(),request.monto());
    }
}
