package cloud.cholewa.gateway.routes;

import cloud.cholewa.gateway.config.InternalServicesConfig;
import cloud.cholewa.gateway.config.ServiceUri;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

@Configuration
public class RoutesConfig {

    //predicates are written without /home: PathRoutePredicateFactory prepends spring.webflux.base-path
    //to every pattern and matches it against the full request path, so nothing is stripped anywhere -
    //which is also why the target receives the path unchanged (RouteToRequestUrlFilter merges only
    //scheme, host and port). Every service is mounted under the same path externally as internally,
    //so no rewrite is needed; a target with a different path needs rewritePath, not a longer uri()
    @Bean
    RouteLocator homeRoutes(final RouteLocatorBuilder builder, final InternalServicesConfig services) {
        return builder.routes()
            //an OpenAI answer takes far longer than anything else behind this gateway, so this route
            //overrides the global response-timeout instead of dragging it up for everyone
            .route("ai", r -> r.path("/ai", "/ai/**")
                .metadata(RESPONSE_TIMEOUT_ATTR, 120000L)
                .uri(uri(services.ai())))
            .route("amx", r -> r.path("/amx", "/amx/**").uri(uri(services.amx())))
            .route("boiler", r -> r.path("/boiler", "/boiler/**").uri(uri(services.boiler())))
            .route("database", r -> r.path("/device/configuration/**").uri(uri(services.database())))
            .route("heating", r -> r.path("/heating", "/heating/**").uri(uri(services.heating())))
            .route("water", r -> r.path("/water", "/water/**").uri(uri(services.water())))
            .build();
    }

    private static String uri(final ServiceUri service) {
        return service.uri();
    }
}
