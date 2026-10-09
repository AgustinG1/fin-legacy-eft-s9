package cl.duoc.eft.services.accounts;

import cl.duoc.eft.services.shared.*;
import cl.duoc.eft.services.shared.Models.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:accounts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa","spring.datasource.password=","eft.outbound.enabled=false","INTERNAL_SECRET=test-only-not-a-real-secret"})
class AccountIntegrationTest {
    @Autowired AccountService service;
    @SpyBean JdbcTemplate jdbc;
    @MockBean CustomerGateway customers;
    @MockBean RestClient client;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM ledger_entry"); jdbc.update("DELETE FROM ledger_operation"); jdbc.update("DELETE FROM account");
    }
    Cuenta open() { return service.open(new AbrirCuenta(UUID.randomUUID(),UUID.randomUUID(),TipoCuenta.CORRIENTE,"Prueba")); }
    OrdenPago order(TipoPago type,UUID from,UUID to,String amount) {
        return new OrdenPago(UUID.randomUUID(),type,from,to,new BigDecimal(amount),"Prueba");
    }
    @Test void transferIsBalancedAndIdempotent() {
        var a=open(); var b=open();
        service.apply(order(TipoPago.DEPOSITO,null,a.id(),"100"));
        var transfer=order(TipoPago.TRANSFERENCIA,a.id(),b.id(),"40");
        var receipt=service.apply(transfer);
        assertThat(service.apply(transfer)).isEqualTo(receipt);
        assertThat(service.get(a.id()).saldo()).isEqualByComparingTo("60");
        assertThat(service.get(b.id()).saldo()).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT SUM(delta) FROM ledger_entry WHERE operation_id=?",BigDecimal.class,transfer.id().toString())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE operation_id=?",Integer.class,transfer.id().toString())).isEqualTo(2);
        assertThatThrownBy(()->service.apply(new OrdenPago(transfer.id(),transfer.tipo(),a.id(),b.id(),new BigDecimal("41"),"Prueba")))
                .isInstanceOf(BusinessException.class);
    }
    @Test void overdraftAndClosedAccountDoNotChangeBalances() {
        var a=open(); var b=open();
        service.apply(order(TipoPago.DEPOSITO,null,a.id(),"20"));
        assertThatThrownBy(()->service.apply(order(TipoPago.RETIRO,a.id(),null,"21"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.close(a.id(),service.get(a.id()).version())).isInstanceOf(BusinessException.class);
        service.close(b.id(),0);
        assertThatThrownBy(()->service.apply(order(TipoPago.TRANSFERENCIA,a.id(),b.id(),"10"))).isInstanceOf(BusinessException.class);
        assertThat(service.get(a.id()).saldo()).isEqualByComparingTo("20");
        assertThat(service.get(b.id()).saldo()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_operation",Integer.class)).isEqualTo(1);
    }
    @Test void journalFailureRollsBackBothSidesAndReceipt() {
        var a=open(); var b=open();
        service.apply(order(TipoPago.DEPOSITO,null,a.id(),"100"));
        var transfer=order(TipoPago.TRANSFERENCIA,a.id(),b.id(),"40");
        // Fail the credit entry after the debit, credit balance and receipt have been written.
        doThrow(new DataIntegrityViolationException("simulated journal failure")).when(jdbc).update(
                eq("INSERT INTO ledger_entry(operation_id,account_id,delta,balance_after) VALUES(?,?,?,?)"),
                eq(transfer.id().toString()),eq(b.id().toString()),any(BigDecimal.class),any(BigDecimal.class));
        assertThatThrownBy(()->service.apply(transfer)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.get(a.id()).saldo()).isEqualByComparingTo("100");
        assertThat(service.get(b.id()).saldo()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_operation WHERE id=?",Integer.class,transfer.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE operation_id=?",Integer.class,transfer.id().toString())).isZero();
    }
    @Test void concurrentWithdrawalsCannotOverdraw() throws Exception {
        var a=open(); service.apply(order(TipoPago.DEPOSITO,null,a.id(),"100"));
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> withdraw=()->{start.await(); try { service.apply(order(TipoPago.RETIRO,a.id(),null,"80")); return true; }
                catch(BusinessException expected) { return false; }};
            var one=pool.submit(withdraw); var two=pool.submit(withdraw); start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(service.get(a.id()).saldo()).isEqualByComparingTo("20");
        } finally { pool.shutdownNow(); }
    }
    @Test void oppositeTransfersAndDuplicateRequestsRemainConsistent() throws Exception {
        var a=open(); var b=open();
        service.apply(order(TipoPago.DEPOSITO,null,a.id(),"100")); service.apply(order(TipoPago.DEPOSITO,null,b.id(),"100"));
        var forward=order(TipoPago.TRANSFERENCIA,a.id(),b.id(),"30");
        var reverse=order(TipoPago.TRANSFERENCIA,b.id(),a.id(),"10");
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=List.of(forward,reverse,forward).stream().map(o->pool.submit(()->{start.await();return service.apply(o);})).toList();
            start.countDown();
            for(var job:jobs) assertThat(job.get(10,TimeUnit.SECONDS)).isNotNull();
            assertThat(service.get(a.id()).saldo()).isEqualByComparingTo("80");
            assertThat(service.get(b.id()).saldo()).isEqualByComparingTo("120");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_operation",Integer.class)).isEqualTo(4);
        } finally { pool.shutdownNow(); }
    }
    @Test void nonexistentCustomerAndStaleVersionAreRejected() {
        doThrow(BusinessException.invalid("Cliente inexistente")).when(customers).requireCustomer(any());
        assertThatThrownBy(this::open).isInstanceOf(BusinessException.class);
        assertThat(service.list(null)).isEmpty();
        reset(customers);
        var account=open(); service.update(account.id(),new EditarCuenta("Nuevo",0L));
        assertThatThrownBy(()->service.update(account.id(),new EditarCuenta("Viejo",0L))).isInstanceOf(BusinessException.class);
        assertThat(service.get(account.id()).alias()).isEqualTo("Nuevo");
    }
}
