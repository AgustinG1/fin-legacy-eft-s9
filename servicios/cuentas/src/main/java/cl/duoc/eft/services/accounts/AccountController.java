package cl.duoc.eft.services.accounts;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    private final AccountService service;
    public AccountController(AccountService service) { this.service=service; }
    @GetMapping("/api/cuentas") @PreAuthorize("hasAuthority('SCOPE_cuentas.read')")
    public List<Cuenta> list(@RequestParam(required=false) UUID clienteId) { return service.list(clienteId); }
    @GetMapping("/api/cuentas/{id}") @PreAuthorize("hasAuthority('SCOPE_cuentas.read')")
    public Cuenta get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/api/cuentas") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('SCOPE_cuentas.write')")
    public Cuenta open(@Valid @RequestBody AbrirCuenta request) { return service.open(request); }
    @PutMapping("/api/cuentas/{id}") @PreAuthorize("hasAuthority('SCOPE_cuentas.write')")
    public Cuenta update(@PathVariable UUID id,@Valid @RequestBody EditarCuenta request) { return service.update(id,request); }
    @PostMapping("/api/cuentas/{id}/cierre") @PreAuthorize("hasAuthority('SCOPE_cuentas.write')")
    public Cuenta close(@PathVariable UUID id,@RequestParam long version) { return service.close(id,version); }
    @PostMapping("/interno/movimientos") @PreAuthorize("hasAuthority('SCOPE_ledger.write')")
    public Comprobante apply(@Valid @RequestBody OrdenPago request) { return service.apply(request); }
}
