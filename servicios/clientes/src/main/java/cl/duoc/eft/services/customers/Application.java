package cl.duoc.eft.services.customers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"cl.duoc.eft.services.customers","cl.duoc.eft.services.shared"})
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class,args); }
}
