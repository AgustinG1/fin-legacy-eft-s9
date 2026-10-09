package cl.duoc.eft.services.channels;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
@Service
public class ChannelService {
    private final RestClient http;
    private final DependencyProtection protection;
    private final BigDecimal maximum,denomination;
    public ChannelService(RestClient http,DependencyProtection protection,
            @Value("${eft.atm.max-withdrawal:200000}") BigDecimal maximum,@Value("${eft.atm.denomination:1000}") BigDecimal denomination) {
        if(maximum.signum()<=0 || denomination.signum()<=0) throw new IllegalArgumentException("Politica de cajero invalida");
        this.http=http; this.protection=protection; this.maximum=maximum; this.denomination=denomination;
    }
    public UUID customer(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("cliente_id")); }
        catch(Exception ex) { throw new BusinessException(HttpStatus.FORBIDDEN,"Token sin identidad de cliente valida"); }
    }
    public Cliente profile(Jwt jwt) { return get("clientes","/api/clientes/"+customer(jwt),Cliente.class); }
    public List<Cuenta> accounts(Jwt jwt) {
        UUID id=customer(jwt);
        var result=protection.read("cuentas",()->http.get().uri("http://cuentas/api/cuentas?clienteId="+id)
                .retrieve().body(new ParameterizedTypeReference<List<Cuenta>>(){}));
        if(result==null || result.stream().anyMatch(a->!id.equals(a.clienteId())))
            throw new ResourceAccessException("Respuesta de cuentas invalida");
        return result;
    }
    public Cuenta ownAccount(Jwt jwt,UUID account) {
        UUID id=customer(jwt);
        Cuenta value=get("cuentas","/api/cuentas/"+account,Cuenta.class);
        if(!id.equals(value.clienteId())) throw new BusinessException(HttpStatus.FORBIDDEN,"Cuenta ajena al cliente autenticado");
        return value;
    }
    public Pago pay(Jwt jwt,OrdenPago request) {
        PaymentRules.validate(request);
        ownAccount(jwt,request.origen()!=null?request.origen():request.destino());
        return protection.call("pagos",()->http.post().uri("http://pagos/api/pagos").body(request).retrieve().body(Pago.class));
    }
    public Pago withdraw(Jwt jwt,UUID account,UUID id,BigDecimal amount) {
        if(amount==null || amount.signum()<=0 || amount.compareTo(maximum)>0 || amount.remainder(denomination).signum()!=0)
            throw BusinessException.invalid("Monto fuera del limite o denominacion del cajero");
        return pay(jwt,new OrdenPago(id,TipoPago.RETIRO,account,null,amount,"Retiro por cajero"));
    }
    private <T> T get(String service,String path,Class<T> type) {
        var response=protection.read(service,()->http.get().uri("http://"+service+path).retrieve().toEntity(type));
        var context=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(context instanceof org.springframework.web.context.request.ServletRequestAttributes attributes && attributes.getResponse()!=null) {
            String instance=response.getHeaders().getFirst("X-EFT-Instance");
            if(instance!=null) attributes.getResponse().setHeader("X-EFT-Upstream-"+service,instance);
        }
        T result=response.getBody();
        if(result==null) throw new ResourceAccessException("Respuesta vacia");
        return result;
    }
}
