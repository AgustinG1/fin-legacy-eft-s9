package cl.duoc.eft.services.customers;
import cl.duoc.eft.services.shared.Models.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/clientes")
public class CustomerController {
    private final CustomerService service;
    public CustomerController(CustomerService service) { this.service=service; }
    @GetMapping @PreAuthorize("hasAuthority('SCOPE_clientes.read')")
    public List<Cliente> list() { return service.list(); }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('SCOPE_clientes.read')")
    public Cliente get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('SCOPE_clientes.write')")
    public Cliente create(@Valid @RequestBody NuevoCliente request) { return service.create(request); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('SCOPE_clientes.write')")
    public Cliente update(@PathVariable UUID id,@Valid @RequestBody EditarCliente request) { return service.update(id,request); }
}
