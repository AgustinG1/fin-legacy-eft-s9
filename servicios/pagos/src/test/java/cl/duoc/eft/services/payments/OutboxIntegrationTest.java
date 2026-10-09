package cl.duoc.eft.services.payments;
import cl.duoc.eft.services.shared.Models.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.client.RestClient;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=",
        "eft.outbound.enabled=false","INTERNAL_SECRET=test-not-a-real-secret-123456","eft.kafka.enabled=true","eft.kafka.outbox-initial-delay=86400000"})
class OutboxIntegrationTest {
    @Autowired PaymentStore store;
    @Autowired OutboxPublisher publisher;
    @SpyBean JdbcTemplate jdbc;
    @MockBean KafkaTemplate<String,String> kafka;
    @MockBean RestClient client;
    UUID prepare(){
        UUID id=UUID.randomUUID(),account=UUID.randomUUID();
        var request=new OrdenPago(id,TipoPago.DEPOSITO,null,account,new BigDecimal("100.00"),"Prueba");
        store.reserve(request,"test");
        store.complete(id,new Comprobante(id,TipoPago.DEPOSITO,null,account,request.monto(),null,request.monto(),Instant.now()));
        return id;
    }
    @BeforeEach void clean(){jdbc.update("DELETE FROM payment_outbox");jdbc.update("DELETE FROM payment_request");}
    @Test void failedBrokerKeepsPendingThenAcknowledgedRetryMarksPublished(){
        UUID id=prepare();
        when(kafka.send(eq("eft.pagos.v1"),eq(id.toString()),anyString()))
            .thenReturn(CompletableFuture.<SendResult<String,String>>failedFuture(new IllegalStateException("broker down")))
            .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish();
        assertThat(store.get(id).estado()).isEqualTo("COMPLETADO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox WHERE published_at IS NULL",Integer.class)).isEqualTo(1);
        publisher.publish();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox WHERE published_at IS NOT NULL",Integer.class)).isEqualTo(1);
        publisher.publish();verify(kafka,times(2)).send(eq("eft.pagos.v1"),eq(id.toString()),anyString());
    }
    @Test void lostDatabaseConfirmationAfterBrokerAckLeavesEventAvailableForRedelivery(){
        UUID id=prepare();
        when(kafka.send(eq("eft.pagos.v1"),eq(id.toString()),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new TransientDataAccessResourceException("database confirmation lost")).doCallRealMethod().when(jdbc)
            .update(eq("UPDATE payment_outbox SET published_at=CURRENT_TIMESTAMP WHERE event_id=?"),eq(id.toString()));
        publisher.publish();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox WHERE published_at IS NULL",Integer.class)).isEqualTo(1);
        publisher.publish();
        verify(kafka,times(2)).send(eq("eft.pagos.v1"),eq(id.toString()),anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox WHERE published_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }
}
