package cl.duoc.eft.services.payments;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pagos")
public class PaymentController {
    private final PaymentService service;
    private final PaymentStore store;
    public PaymentController(PaymentService service,PaymentStore store) { this.service=service; this.store=store; }
    @PostMapping @PreAuthorize("hasAuthority('SCOPE_pagos.write')")
    public Pago execute(@Valid @RequestBody OrdenPago request,Authentication authentication) {
        return service.execute(request,authentication.getName());
    }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('SCOPE_pagos.read')")
    public Pago get(@PathVariable UUID id) { return store.get(id); }
}
