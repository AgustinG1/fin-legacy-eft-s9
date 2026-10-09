package cl.duoc.eft.services.shared;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.*;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
class DependencyProtectionTest {
    @Test void readRecoversOnThirdAttemptButBusinessErrorsAndWritesAreNotRetried() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        var calls=new AtomicInteger();
        assertThat(protection.read("lectura",()->{
            if(calls.incrementAndGet()<3) throw new ResourceAccessException("temporary");
            return "ok";
        })).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
        calls.set(0);
        assertThatThrownBy(()->protection.read("negocio",()->{
            calls.incrementAndGet(); throw HttpClientErrorException.create(HttpStatus.NOT_FOUND,"missing",null,null,null);
        })).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isEqualTo(1);
        calls.set(0);
        assertThatThrownBy(()->protection.call("escritura",()->{
            calls.incrementAndGet(); throw new ResourceAccessException("temporary");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void readRetriesAreBoundedAndOpenCircuitDoesNotCallTheDependency() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        var calls=new AtomicInteger();
        for(int i=0;i<4;i++) assertThatThrownBy(()->protection.read("agotado",()->{
            calls.incrementAndGet(); throw new ResourceAccessException("offline");
        })).isInstanceOf(ResourceAccessException.class);
        assertThat(calls.get()).isEqualTo(12);
        assertThat(protection.state("agotado")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(()->protection.read("agotado",()->{calls.incrementAndGet();return "no";}))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(calls.get()).isEqualTo(12);
    }
    @Test void opensAfterInfrastructureFailuresAndStopsCallingDependency() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        var calls=new AtomicInteger();
        for(int i=0;i<4;i++) assertThatThrownBy(()->protection.call("cuentas",()->{calls.incrementAndGet();throw new ResourceAccessException("timeout");}))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(protection.state("cuentas")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(()->protection.call("cuentas",()->{calls.incrementAndGet();return "nunca";})).isInstanceOf(ResourceAccessException.class);
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void businessRejectionsDoNotOpenCircuit() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        for(int i=0;i<8;i++) assertThatThrownBy(()->protection.call("cuentas",()->{throw BusinessException.conflict("Saldo insuficiente");}))
                .isInstanceOf(BusinessException.class);
        assertThat(protection.state("cuentas")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(protection.call("cuentas",()->"ok")).isEqualTo("ok");
    }
    @Test void unavailableResponseNeverPretendsSuccess() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        assertThatThrownBy(()->protection.call("pagos",()->{throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,"down",null,null,null);}))
                .isInstanceOf(ResourceAccessException.class);
    }
    @Test void emptyDiscoveryRegistryIsTemporaryUnavailability() {
        var protection=new DependencyProtection(new ObjectMapper(),4,10);
        assertThatThrownBy(()->protection.call("cuentas",()->{throw new IllegalStateException("No instances available for cuentas");}))
                .isInstanceOf(ResourceAccessException.class);
    }
}
