package cl.duoc.eft.services.payments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="eft.kafka.enabled",havingValue="true")
public class KafkaPublishing {}
