package cl.duoc.eft.services.audit;
import cl.duoc.eft.services.shared.PaymentEvent;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
@Configuration @ConditionalOnProperty(name="eft.kafka.enabled",havingValue="true")
public class KafkaConsuming {
    private final AuditStore store;
    public KafkaConsuming(AuditStore store){this.store=store;}
    @KafkaListener(topics="eft.pagos.v1",concurrency="3")
    public void receive(ConsumerRecord<String,String> record) {
        store.record(record.key(),record.value(),record.topic(),record.partition(),record.offset());
    }
    @Bean DefaultErrorHandler errorHandler(KafkaTemplate<String,String> template) {
        var recoverer=new DeadLetterPublishingRecoverer(template,(r,e)->new TopicPartition("eft.pagos.v1.DLT",r.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(6));
        var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(500,2));
        handler.addNotRetryableExceptions(PaymentEvent.Invalid.class);
        return handler;
    }
}
