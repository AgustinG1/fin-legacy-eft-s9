package cl.duoc.eft.services.config;
import org.springframework.cloud.config.server.EnableConfigServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@EnableConfigServer
@SpringBootApplication(scanBasePackages={"cl.duoc.eft.services.config","cl.duoc.eft.services.infra"})
public class Application { public static void main(String[] args) { SpringApplication.run(Application.class,args); } }
