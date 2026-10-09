package cl.duoc.eft.services.payments;

import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public PaymentStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.tx=new TransactionTemplate(manager);
    }
    public Pago reserve(OrdenPago request,String caller) {
        String fingerprint=PaymentRules.fingerprint(request);
        try {
            return tx.execute(status->{
                var rows=jdbc.queryForList("SELECT * FROM payment_request WHERE id=?",request.id().toString());
                if(!rows.isEmpty()) return existing(rows.get(0),fingerprint,caller);
                jdbc.update("INSERT INTO payment_request(id,fingerprint,caller,request_json,status) VALUES(?,?,?,?,'PENDIENTE')",
                        request.id().toString(),fingerprint,caller,encode(request));
                return new Pago(request.id(),"PENDIENTE",null,null);
            });
        } catch(DataIntegrityViolationException collision) {
            // Read only after the losing insert's transaction has rolled back.
            var rows=jdbc.queryForList("SELECT * FROM payment_request WHERE id=?",request.id().toString());
            if(rows.isEmpty()) throw collision;
            return existing(rows.get(0),fingerprint,caller);
        }
    }
    private Pago existing(Map<String,Object> row,String fingerprint,String caller) {
        if(!row.get("fingerprint").equals(fingerprint) || !row.get("caller").equals(caller))
            throw BusinessException.conflict("Solicitud reutilizada con otros datos o cliente OAuth2");
        return map(row);
    }
    public Pago get(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM payment_request WHERE id=?",id.toString());
        if(rows.isEmpty()) throw BusinessException.missing("Pago inexistente");
        return map(rows.get(0));
    }
    public Pago complete(UUID id,Comprobante receipt) {
        return tx.execute(status->{
            var row=jdbc.queryForMap("SELECT * FROM payment_request WHERE id=? FOR UPDATE",id.toString());
            if(row.get("status").equals("COMPLETADO")) return map(row);
            if(!row.get("status").equals("PENDIENTE")) throw BusinessException.conflict("Solicitud rechazada previamente");
            String encoded=encode(receipt);
            jdbc.update("UPDATE payment_request SET status='COMPLETADO',receipt=? WHERE id=?",encoded,id.toString());
            jdbc.update("INSERT INTO payment_outbox(event_id,event_type,payload) VALUES(?,'PAGO_COMPLETADO',?)",id.toString(),encoded);
            return get(id);
        });
    }
    public void reject(UUID id,String reason) {
        tx.executeWithoutResult(status->jdbc.update("UPDATE payment_request SET status='RECHAZADO',reason=? WHERE id=? AND status='PENDIENTE'",
                reason.length()>255?reason.substring(0,255):reason,id.toString()));
    }
    private Pago map(Map<String,Object> row) {
        Comprobante receipt=null;
        if(row.get("receipt")!=null) {
            try { receipt=json.readValue(row.get("receipt").toString(),Comprobante.class); }
            catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
        }
        return new Pago(UUID.fromString(row.get("id").toString()),row.get("status").toString(),receipt,(String)row.get("reason"));
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
