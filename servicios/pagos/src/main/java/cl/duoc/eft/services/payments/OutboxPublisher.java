package cl.duoc.eft.services.payments;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.Comprobante;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component
@ConditionalOnProperty(name="eft.kafka.enabled",havingValue="true")
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public OutboxPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.kafka=kafka;this.json=json;tx=new TransactionTemplate(manager);tx.setTimeout(15);
    }
    @Scheduled(fixedDelayString="${eft.kafka.outbox-delay:3000}",initialDelayString="${eft.kafka.outbox-initial-delay:3000}")
    public void publish() {
        for(int i=0;i<20;i++) {
            try {
                Boolean sent=tx.execute(status->{
                    var rows=jdbc.queryForList("SELECT event_id,payload FROM payment_outbox WHERE published_at IS NULL ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if(rows.isEmpty()) return false;
                    String id=rows.get(0).get("event_id").toString();
                    try {
                        var receipt=json.readValue(rows.get(0).get("payload").toString(),Comprobante.class);
                        var event=new PaymentEvent(1,"PAGO_COMPLETADO",UUID.fromString(id),receipt);
                        kafka.send("eft.pagos.v1",id,event.encode(json)).get(6,TimeUnit.SECONDS);
                    } catch(Exception ex) {
                        if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                        throw new IllegalStateException("Publicacion pendiente",ex);
                    }
                    jdbc.update("UPDATE payment_outbox SET published_at=CURRENT_TIMESTAMP WHERE event_id=?",id);
                    return true;
                });
                if(!Boolean.TRUE.equals(sent)) return;
            } catch(RuntimeException ex) {
                log.warn("KAFKA_PUBLICACION_PENDIENTE: se conserva outbox y se reintentara en el siguiente ciclo");
                return;
            }
        }
    }
}
