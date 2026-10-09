package cl.duoc.eft.services.audit;
import cl.duoc.eft.services.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class AuditStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public AuditStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){this.jdbc=jdbc;this.json=json;tx=new TransactionTemplate(manager);}
    public void record(String source,String key,String value,String topic,int partition,long offset) {
        PaymentEvent event=PaymentEvent.read(key,value,json);
        try {tx.executeWithoutResult(s->insert(source,event,topic,partition,offset));}
        catch(DataIntegrityViolationException collision) {
            // A concurrent delivery can win the first insert. Re-read after rollback.
            tx.executeWithoutResult(s->insert(source,event,topic,partition,offset));
        }
    }
    private void insert(String source,PaymentEvent event,String topic,int partition,long offset) {
        String id=event.id().toString(), fingerprint=event.fingerprint(json);
        var previous=jdbc.queryForList("SELECT fingerprint FROM audit_event WHERE event_id=? FOR UPDATE",id);
        if(previous.isEmpty()) jdbc.update("INSERT INTO audit_event(event_id,fingerprint,payload) VALUES(?,?,?)",id,fingerprint,event.encode(json));
        else if(!previous.get(0).get("fingerprint").equals(fingerprint)) throw new PaymentEvent.Invalid("ID de evento reutilizado con otro comprobante");
        var delivery=jdbc.queryForList("SELECT event_id FROM audit_delivery_v2 WHERE broker_source=? AND broker_topic=? AND broker_partition=? AND broker_offset=?",source,topic,partition,offset);
        if(delivery.isEmpty()) jdbc.update("INSERT INTO audit_delivery_v2(broker_source,broker_topic,broker_partition,broker_offset,event_id) VALUES(?,?,?,?,?)",source,topic,partition,offset,id);
        else if(!delivery.get(0).get("event_id").equals(id)) throw new PaymentEvent.Invalid("Posicion Kafka reutilizada con otro evento");
    }
    public record Receipt(PaymentEvent evento,long entregas) {}
    public Receipt get(UUID id) {
        var rows=jdbc.queryForList("SELECT payload FROM audit_event WHERE event_id=?",id.toString());
        if(rows.isEmpty()) throw BusinessException.missing("Evento aun no auditado");
        PaymentEvent event=PaymentEvent.read(id.toString(),rows.get(0).get("payload").toString(),json);
        long deliveries=jdbc.queryForObject("SELECT COUNT(*) FROM audit_delivery_v2 WHERE event_id=?",Long.class,id.toString());
        return new Receipt(event,deliveries);
    }
}
