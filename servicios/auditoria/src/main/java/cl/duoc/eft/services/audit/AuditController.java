package cl.duoc.eft.services.audit;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auditoria")
public class AuditController {
    private final AuditStore store;
    public AuditController(AuditStore store){this.store=store;}
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('SCOPE_auditoria.read')")
    public AuditStore.Receipt get(@PathVariable UUID id){return store.get(id);}
}
