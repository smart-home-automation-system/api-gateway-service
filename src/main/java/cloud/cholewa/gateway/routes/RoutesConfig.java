package cloud.cholewa.gateway.routes;

import cloud.cholewa.gateway.config.HostWaterHeatingConfig;
import cloud.cholewa.gateway.routes.water.WaterRoutes;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {

    @Bean
    RouteLocator homeRoutes(
        final RouteLocatorBuilder builder,
        final WaterRoutes waterRoutes,
        final HostWaterHeatingConfig config
    ) {
        RouteLocatorBuilder.Builder routes = builder.routes();

        waterRoutes.configureWaterRoutes(routes, config);

        return routes.build();
    }
}
