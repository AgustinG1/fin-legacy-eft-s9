package cl.duoc.eft.services.accounts;
import cl.duoc.eft.services.shared.BusinessException;
import cl.duoc.eft.services.shared.DependencyProtection;
import cl.duoc.eft.services.shared.Models.Cliente;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
@Component
public class HttpCustomerGateway implements CustomerGateway {
    private final RestClient client;
    private final DependencyProtection protection;
    public HttpCustomerGateway(RestClient client,DependencyProtection protection) { this.client=client; this.protection=protection; }
    public void requireCustomer(UUID id) {
        protection.call("clientes",()->{ check(id); return true; });
    }
    private void check(UUID id) {
        try {
            Cliente customer=client.get().uri("/api/clientes/{id}",id).retrieve().body(Cliente.class);
            if(customer==null || !customer.id().equals(id)) throw new ResourceAccessException("Respuesta de clientes invalida");
        } catch(HttpClientErrorException.NotFound ex) { throw BusinessException.invalid("Cliente inexistente"); }
    }
}
