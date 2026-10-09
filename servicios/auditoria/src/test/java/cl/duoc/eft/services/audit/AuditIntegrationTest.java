package cl.duoc.eft.services.audit;
import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","spring.datasource.username=sa","spring.datasource.password="})
class AuditIntegrationTest {
    @Autowired AuditStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @BeforeEach void clean(){jdbc.update("DELETE FROM audit_delivery_v2");jdbc.update("DELETE FROM audit_delivery");jdbc.update("DELETE FROM audit_event");}
    PaymentEvent event(){
        UUID id=UUID.randomUUID();
        return new PaymentEvent(1,"PAGO_COMPLETADO",id,new Comprobante(id,TipoPago.DEPOSITO,null,UUID.randomUUID(),new BigDecimal("100.00"),null,new BigDecimal("100.00"),Instant.parse("2026-01-01T00:00:00Z")));
    }
    @Test void repeatsRecordAndDeliveryWithoutDuplicatingAudit(){
        var event=event();String id=event.id().toString(),value=event.encode(json);
        store.record("local",id,value,"eft.pagos.v1",0,10);store.record("local",id,value,"eft.pagos.v1",0,10);
        store.record("local",id,value,"eft.pagos.v1",0,11);
        assertThat(store.get(event.id()).entregas()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Integer.class)).isEqualTo(1);
    }
    @Test void independentBrokersCanReusePositionsButOneBrokerCannot(){
        var local=event();var remote=event();var conflicting=event();
        store.record("local",local.id().toString(),local.encode(json),"eft.pagos.v1",1,0);
        store.record("aws-lab",remote.id().toString(),remote.encode(json),"eft.pagos.v1",1,0);
        store.record("aws-lab",remote.id().toString(),remote.encode(json),"eft.pagos.v1",1,0);
        assertThat(store.get(local.id()).entregas()).isEqualTo(1);
        assertThat(store.get(remote.id()).entregas()).isEqualTo(1);
        assertThatThrownBy(()->store.record("aws-lab",conflicting.id().toString(),conflicting.encode(json),"eft.pagos.v1",1,0))
            .isInstanceOf(PaymentEvent.Invalid.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Integer.class)).isEqualTo(2);
    }
    @Test void migrationPreservesLegacyDeliveriesAndCanRunAgain(){
        var old=event();var remote=event();
        jdbc.update("INSERT INTO audit_event(event_id,fingerprint,payload) VALUES(?,?,?)",old.id().toString(),old.fingerprint(json),old.encode(json));
        jdbc.update("INSERT INTO audit_delivery(broker_topic,broker_partition,broker_offset,event_id) VALUES(?,?,?,?)","eft.pagos.v1",1,0,old.id().toString());
        var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"));
        migration.execute(jdbc.getDataSource());
        store.record("aws-lab",remote.id().toString(),remote.encode(json),"eft.pagos.v1",1,0);
        migration.execute(jdbc.getDataSource());
        assertThat(store.get(old.id()).entregas()).isEqualTo(1);
        assertThat(store.get(remote.id()).entregas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_delivery",Integer.class)).isEqualTo(1);
    }
    @Test void rejectsChangedPayloadAndPreservesOriginal(){
        var event=event();String id=event.id().toString(),value=event.encode(json);
        store.record("local",id,value,"eft.pagos.v1",0,10);
        String changed=value.replace("100.00","101.00");
        assertThatThrownBy(()->store.record("local",id,changed,"eft.pagos.v1",1,11)).isInstanceOf(PaymentEvent.Invalid.class);
        assertThat(store.get(event.id()).evento()).isEqualTo(event);
        assertThat(store.get(event.id()).entregas()).isEqualTo(1);
    }
    @Test void malformedOrUnmatchedKeyNeverWritesAudit(){
        var event=event();
        assertThatThrownBy(()->store.record("local","bad","no json","eft.pagos.v1",0,10)).isInstanceOf(PaymentEvent.Invalid.class);
        assertThatThrownBy(()->store.record("local",UUID.randomUUID().toString(),event.encode(json),"eft.pagos.v1",0,11)).isInstanceOf(PaymentEvent.Invalid.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Integer.class)).isZero();
    }
    @Test void simultaneousDeliveriesHaveOneAuditAndAllPositions() throws Exception {
        var event=event();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++){final int partition=i;tasks.add(pool.submit(()->{start.await();store.record("local",event.id().toString(),event.encode(json),"eft.pagos.v1",partition,10);return true;}));}
            start.countDown();for(var task:tasks)task.get(10,TimeUnit.SECONDS);
            assertThat(store.get(event.id()).entregas()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
}
