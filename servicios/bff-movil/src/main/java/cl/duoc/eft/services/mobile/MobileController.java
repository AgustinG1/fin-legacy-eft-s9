package cl.duoc.eft.services.mobile;
import cl.duoc.eft.services.channels.ChannelService;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/movil")
public class MobileController {
    private final ChannelService channels;
    public MobileController(ChannelService channels) { this.channels=channels; }
    public record CuentaBreve(UUID id,String alias,BigDecimal saldo,String moneda) {}
    public record Resumen(String nombre,List<CuentaBreve> cuentas) {}
    @GetMapping("/resumen") @PreAuthorize("hasAuthority('SCOPE_movil.read')")
    public Resumen summary(@AuthenticationPrincipal Jwt jwt) {
        return new Resumen(channels.profile(jwt).nombre(),channels.accounts(jwt).stream()
                .map(a->new CuentaBreve(a.id(),a.alias(),a.saldo(),a.moneda())).toList());
    }
    @PostMapping("/pagos") @PreAuthorize("hasAuthority('SCOPE_movil.write')")
    public Pago pay(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody OrdenPago order) { return channels.pay(jwt,order); }
}
