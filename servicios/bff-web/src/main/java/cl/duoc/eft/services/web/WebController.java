package cl.duoc.eft.services.web;
import cl.duoc.eft.services.channels.ChannelService;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/web")
public class WebController {
    private final ChannelService channels;
    public WebController(ChannelService channels) { this.channels=channels; }
    public record Perfil(Cliente cliente,List<Cuenta> cuentas) {}
    @GetMapping("/perfil") @PreAuthorize("hasAuthority('SCOPE_web.read')")
    public Perfil profile(@AuthenticationPrincipal Jwt jwt) { return new Perfil(channels.profile(jwt),channels.accounts(jwt)); }
    @PostMapping("/pagos") @PreAuthorize("hasAuthority('SCOPE_web.write')")
    public Pago pay(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody OrdenPago order) { return channels.pay(jwt,order); }
}
