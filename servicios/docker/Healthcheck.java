import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;

public class Healthcheck {
    public static void main(String[] args) {
        try {
            boolean tls=Boolean.parseBoolean(System.getenv("SERVER_SSL_ENABLED"));
            var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2));
            if(tls) {
                var store=KeyStore.getInstance("PKCS12");
                try(var in=Files.newInputStream(Path.of(URI.create(System.getenv("SERVER_SSL_KEY_STORE"))))) {
                    store.load(in,System.getenv("SERVER_SSL_KEY_STORE_PASSWORD").toCharArray());
                }
                var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store);
                var context=SSLContext.getInstance("TLS");
                context.init(null,trust.getTrustManagers(),null);
                builder.sslContext(context);
            }
            var client=builder.build();
            var request=HttpRequest.newBuilder(URI.create((tls?"https":"http")+"://localhost:"+System.getenv("SERVER_PORT")+"/actuator/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode()==200?0:1);
        } catch(Exception ex) { System.exit(1); }
    }
}
