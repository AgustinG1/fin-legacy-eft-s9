package cl.duoc.eft.services.shared;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public record PaymentEvent(int version,String tipo,UUID id,Comprobante comprobante) {
    public static class Invalid extends RuntimeException { public Invalid(String message) {super(message);} }
    public static PaymentEvent read(String key,String value,ObjectMapper json) {
        try {
            PaymentEvent event=json.readValue(value,PaymentEvent.class);
            if(event==null || event.version()!=1 || !"PAGO_COMPLETADO".equals(event.tipo()) || event.id()==null ||
                    !event.id().toString().equals(key) || event.comprobante()==null || !event.id().equals(event.comprobante().id()))
                throw new Invalid("Contrato o identificador de evento invalido");
            Comprobante c=event.comprobante();
            PaymentRules.validate(new OrdenPago(c.id(),c.tipo(),c.origen(),c.destino(),c.monto(),"Evento auditado"));
            if(c.fecha()==null || (c.origen()!=null && (c.saldoOrigen()==null || c.saldoOrigen().signum()<0)) ||
                    (c.destino()!=null && (c.saldoDestino()==null || c.saldoDestino().signum()<0))) throw new Invalid("Comprobante invalido");
            return new PaymentEvent(1,event.tipo(),event.id(),new Comprobante(c.id(),c.tipo(),c.origen(),c.destino(),money(c.monto()),
                    money(c.saldoOrigen()),money(c.saldoDestino()),c.fecha()));
        } catch(Invalid ex) {throw ex;} catch(Exception ex) {throw new Invalid("Evento no cumple el contrato");}
    }
    private static BigDecimal money(BigDecimal value) {return value==null?null:value.setScale(2,java.math.RoundingMode.UNNECESSARY);}
    public String encode(ObjectMapper json) {
        try {return json.writeValueAsString(this);} catch(Exception ex) {throw new IllegalStateException("No se pudo serializar evento",ex);}
    }
    public String fingerprint(ObjectMapper json) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(json).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex) {throw new IllegalStateException(ex);}
    }
}
