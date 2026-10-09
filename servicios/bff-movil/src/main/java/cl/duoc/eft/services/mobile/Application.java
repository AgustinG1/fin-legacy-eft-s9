package cl.duoc.eft.services.mobile;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
@SpringBootApplication(exclude=DataSourceAutoConfiguration.class,scanBasePackages={"cl.duoc.eft.services.mobile","cl.duoc.eft.services.channels","cl.duoc.eft.services.shared"})
public class Application { public static void main(String[] args) { SpringApplication.run(Application.class,args); } }
