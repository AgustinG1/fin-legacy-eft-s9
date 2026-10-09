package cl.duoc.eft.services.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class DependencyProtection {
    private final CircuitBreakerRegistry circuits;
    private final ObjectMapper json;
    private final RetryRegistry retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(3)
            .waitDuration(Duration.ofMillis(150))
            .retryOnException(ex -> ex instanceof ResourceAccessException
                    || ex instanceof HttpServerErrorException
                    || (ex instanceof IllegalStateException && ex.getMessage()!=null
                        && ex.getMessage().startsWith("No instances available")))
            .build());
    public DependencyProtection(ObjectMapper json,@Value("${eft.resilience.minimum-calls:4}") int minimum,
            @Value("${eft.resilience.open-seconds:10}") int seconds) {
        this.json=json;
        var config=CircuitBreakerConfig.custom().slidingWindowSize(Math.max(4,minimum)).minimumNumberOfCalls(minimum)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(seconds)).permittedNumberOfCallsInHalfOpenState(2)
                .recordException(ex->!(ex instanceof BusinessException) &&
                        !(ex instanceof RestClientResponseException response && (response.getStatusCode().value()==400 ||
                                response.getStatusCode().value()==404 || response.getStatusCode().value()==409))).build();
        circuits=CircuitBreakerRegistry.of(config);
    }
    public <T> T call(String name,Supplier<T> operation) {
        try { return circuits.circuitBreaker(name).executeSupplier(operation); }
        catch(CallNotPermittedException ex) { throw new ResourceAccessException("Dependencia temporalmente suspendida por circuito abierto"); }
        catch(IllegalStateException ex) {
            if(ex.getMessage()!=null && ex.getMessage().startsWith("No instances available"))
                throw new ResourceAccessException("Registro de servicio temporalmente no disponible",new java.io.IOException(ex));
            throw ex;
        }
        catch(RestClientResponseException ex) {
            int code=ex.getStatusCode().value();
            if(code==400 || code==404 || code==409) {
                String reason="Solicitud rechazada por servicio de negocio";
                try { reason=json.readTree(ex.getResponseBodyAsString()).path("detail").asText(reason); }
                catch(java.io.IOException ignored) {}
                throw new BusinessException(HttpStatus.valueOf(code),reason);
            }
            throw new ResourceAccessException("Dependencia no disponible",new java.io.IOException(ex));
        }
    }
    public CircuitBreaker.State state(String name) { return circuits.circuitBreaker(name).getState(); }
    /** Retry only read operations. Mutations retain their explicit idempotency workflow. */
    public <T> T read(String name, Supplier<T> operation) {
        return call(name, () -> retries.retry(name).executeSupplier(operation));
    }
}
