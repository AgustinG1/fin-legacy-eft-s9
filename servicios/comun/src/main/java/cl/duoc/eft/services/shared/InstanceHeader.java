package cl.duoc.eft.services.shared;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class InstanceHeader extends OncePerRequestFilter {
    private final String instance,revision;
    public InstanceHeader(@Value("${spring.application.name}") String name,@Value("${HOSTNAME:local}") String host,
            @Value("${eft.config.revision:local}") String revision) { this.instance=name+":"+host; this.revision=revision; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-EFT-Instance",instance); response.setHeader("X-EFT-Config",revision); chain.doFilter(request,response);
    }
}
