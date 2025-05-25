package cloud.cholewa.gateway.routes;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    @Bean
    RouteLocator homeRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route(r -> r.path("/status")
                        .uri("http://localhost:6002/home/status"))
                .build();
    }
}
