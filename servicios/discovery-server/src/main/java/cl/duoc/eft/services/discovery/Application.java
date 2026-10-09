package cl.duoc.eft.services.discovery;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@EnableEurekaServer
@SpringBootApplication(scanBasePackages={"cl.duoc.eft.services.discovery","cl.duoc.eft.services.infra"})
public class Application { public static void main(String[] args) { SpringApplication.run(Application.class,args); } }
