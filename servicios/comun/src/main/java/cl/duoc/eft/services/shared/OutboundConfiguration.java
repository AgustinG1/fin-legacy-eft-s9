package cl.duoc.eft.services.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name="eft.outbound.enabled",havingValue="true")
public class OutboundConfiguration {
    @Bean
    OAuth2AuthorizedClientManager clients(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService service) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(2000);
        var tokens=new RestClientClientCredentialsTokenResponseClient();
        tokens.setRestClient(RestClient.builder().requestFactory(factory).messageConverters(c->{
            c.clear(); c.add(new FormHttpMessageConverter()); c.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var manager=new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations,service);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(c->c.accessTokenResponseClient(tokens)).build());
        return manager;
    }
    @Bean
    @LoadBalanced
    RestClient.Builder balancedBuilder() {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(3000);
        return RestClient.builder().requestFactory(factory);
    }
    @Bean
    RestClient internalClient(OAuth2AuthorizedClientManager clients,@org.springframework.beans.factory.annotation.Qualifier("balancedBuilder") RestClient.Builder balancedBuilder,
            @Value("${eft.dependency.url}") String url,@Value("${spring.application.name}") String name,
            @Value("${eft.cloud.enabled:false}") boolean cloud) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(3000);
        var builder=cloud?balancedBuilder.clone():RestClient.builder().requestFactory(factory);
        return builder.baseUrl(url).requestInterceptor((request,body,execution)->{
            var client=clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("internal").principal(name).build());
            if(client==null) throw new org.springframework.web.client.ResourceAccessException("No se obtuvo token interno");
            request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            return execution.execute(request,body);
        }).build();
    }
}
