package cl.duoc.eft.services.payments;

import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa","spring.datasource.password=","eft.outbound.enabled=false","INTERNAL_SECRET=test-only-not-a-real-secret"})
class PaymentIntegrationTest {
    @Autowired PaymentService service;
    @Autowired PaymentStore store;
    @Autowired JdbcTemplate jdbc;
    @MockBean AccountGateway accounts;
    @MockBean RestClient client;
    @BeforeEach void clean() { jdbc.update("DELETE FROM payment_outbox"); jdbc.update("DELETE FROM payment_request"); }
    OrdenPago order() { return new OrdenPago(UUID.randomUUID(),TipoPago.DEPOSITO,null,UUID.randomUUID(),new BigDecimal("100.00"),"Prueba"); }
    Comprobante receipt(OrdenPago order) { return new Comprobante(order.id(),order.tipo(),null,order.destino(),order.monto(),null,order.monto(),Instant.now()); }
    @Test void timeoutRemainsPendingAndRetryCreatesOneOutboxEvent() {
        var request=order(); var receipt=receipt(request);
        when(accounts.apply(request)).thenThrow(new ResourceAccessException("respuesta perdida despues del cargo")).thenReturn(receipt);
        assertThatThrownBy(()->service.execute(request,"operador")).isInstanceOf(ResourceAccessException.class);
        assertThat(store.get(request.id()).estado()).isEqualTo("PENDIENTE");
        assertThat(service.execute(request,"operador").estado()).isEqualTo("COMPLETADO");
        assertThat(service.execute(request,"operador").comprobante()).isEqualTo(receipt);
        verify(accounts,times(2)).apply(request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox",Integer.class)).isEqualTo(1);
    }
    @Test void sameIdentifierCannotBeUsedByAnotherCallerOrForDifferentAmount() {
        var request=order(); when(accounts.apply(request)).thenReturn(receipt(request));
        service.execute(request,"operador");
        assertThatThrownBy(()->service.execute(request,"otro")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.execute(new OrdenPago(request.id(),request.tipo(),null,request.destino(),new BigDecimal("200"),"Prueba"),"operador"))
                .isInstanceOf(BusinessException.class);
        verify(accounts,times(1)).apply(request);
    }
    @Test void permanentRejectionIsAuditedWithoutPublishingSuccess() {
        var request=order(); when(accounts.apply(request)).thenThrow(BusinessException.conflict("Cuenta cerrada"));
        assertThatThrownBy(()->service.execute(request,"operador")).isInstanceOf(BusinessException.class);
        assertThat(store.get(request.id()).estado()).isEqualTo("RECHAZADO");
        assertThat(store.get(request.id()).motivo()).isEqualTo("Cuenta cerrada");
        assertThatThrownBy(()->service.execute(request,"operador")).isInstanceOf(BusinessException.class);
        verify(accounts,times(1)).apply(request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox",Integer.class)).isZero();
    }
    @Test void simultaneousSubmissionsProduceOneRequestAndOneOutboxEvent() throws Exception {
        var request=order(); var receipt=receipt(request);
        when(accounts.apply(request)).thenReturn(receipt);
        var pool=Executors.newFixedThreadPool(4); var start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<Pago>>();
            for(int i=0;i<4;i++) tasks.add(pool.submit(()->{start.await();return service.execute(request,"operador");}));
            start.countDown();
            for(var task:tasks) assertThat(task.get(10,TimeUnit.SECONDS).comprobante()).isEqualTo(receipt);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_request",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_outbox",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidAmountNeverReachesLedger() {
        var request=order();
        assertThatThrownBy(()->service.execute(new OrdenPago(request.id(),request.tipo(),null,request.destino(),new BigDecimal("0.001"),"Prueba"),"operador"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(accounts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_request",Integer.class)).isZero();
    }
}
