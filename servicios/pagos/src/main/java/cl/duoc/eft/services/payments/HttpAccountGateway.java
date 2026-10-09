package cl.duoc.eft.services.payments;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class HttpAccountGateway implements AccountGateway {
    private final RestClient client;
    private final ObjectMapper json;
    private final DependencyProtection protection;
    public HttpAccountGateway(RestClient client,ObjectMapper json,DependencyProtection protection) { this.client=client; this.json=json; this.protection=protection; }
    public Comprobante apply(OrdenPago order) {
        return protection.call("cuentas",()->send(order));
    }
    private Comprobante send(OrdenPago order) {
        try {
            Comprobante receipt=client.post().uri("/interno/movimientos").body(order).retrieve().body(Comprobante.class);
            if(receipt==null || !receipt.id().equals(order.id())) throw new ResourceAccessException("Respuesta de cuentas invalida");
            return receipt;
        } catch(RestClientResponseException ex) {
            int status=ex.getStatusCode().value();
            if(status==400 || status==404 || status==409) {
                String reason="Operacion rechazada por cuentas";
                try { reason=json.readTree(ex.getResponseBodyAsString()).path("detail").asText(reason); }
                catch(java.io.IOException ignored) { }
                throw new BusinessException(HttpStatus.valueOf(status),reason);
            }
            throw ex;
        }
    }
}
