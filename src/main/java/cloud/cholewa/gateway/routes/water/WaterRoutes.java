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
                .path("/water/hot/status")
                .uri(getUri(config) + "/hot/status"))
            .route(r -> r
                .path("/water/cold/status")
                .uri(getUri(config) + "/cold/status"))
            .build();
    }

    private String getUri(final HostWaterHeatingConfig config) {
        return config.scheme()+ "://" + config.host() + ":" + config.port();
    }
}
