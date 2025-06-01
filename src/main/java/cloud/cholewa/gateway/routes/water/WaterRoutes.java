package cloud.cholewa.gateway.routes.water;

import cloud.cholewa.gateway.config.HostWaterHeatingConfig;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
public class WaterRoutes {

    public void configureWaterRoutes(final RouteLocatorBuilder.Builder builder, final HostWaterHeatingConfig config) {
        builder
            .route(r -> r
                .method(HttpMethod.GET)
                .and()
                .path("/water/hot")
                .uri(getUri(config) + "/home/water/hot"))
            .route(r -> r
                .path("/water/management")
                .uri(getUri(config) + "/home/water/management"))
            .build();
    }

    private String getUri(final HostWaterHeatingConfig config) {
        return config.scheme()+ "://" + config.host() + ":" + config.port();
    }
}
