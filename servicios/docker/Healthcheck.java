import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class Healthcheck {
    public static void main(String[] args) {
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+System.getenv("SERVER_PORT")+"/actuator/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode()==200?0:1);
        } catch(Exception ex) { System.exit(1); }
    }
}
