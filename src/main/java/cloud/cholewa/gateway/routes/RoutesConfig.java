package cloud.cholewa.gateway.routes;

import cloud.cholewa.gateway.config.InternalServicesConfig;
import cloud.cholewa.gateway.config.ServiceUri;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

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
            //everything database-service holds: the Eaton device configuration, the household
            //registry, the device registry and the calendar of the house. By path alone, whatever
            //the method, and with open tails - unlike presence this service has nothing to keep
            //inside, so what it adds below one of these paths is published with it. Only /device
            //is listed by its two branches and not as /device/**
            .route("database", r -> r
                .path(
                    "/device/configuration/**",
                    "/device/registry",
                    "/device/registry/**",
                    "/household",
                    "/household/**",
                    "/calendar/**")
                .uri(uri(services.database())))
            .route("heating", r -> r.path("/heating", "/heating/**").uri(uri(services.heating())))
            //an allowlist, unlike the other routes: exactly the reads of the reporting API, by path
            //and method. /presence/clients lists the MAC address of every device on the home
            //network and has to stay inside the cluster - with a /** tail a path like
            //residents/../clients would match and be forwarded as it is, and whatever that service
            //adds under /residents or /house later would be published without anyone deciding it
            .route("presence", r -> r
                .path(
                    "/presence/residents/presence",
                    "/presence/residents/{name}/report",
                    "/presence/residents/{name}/report/daily",
                    "/presence/house/report")
                .and().method(HttpMethod.GET)
                .uri(uri(services.presence())))
            .route("water", r -> r.path("/water", "/water/**").uri(uri(services.water())))
            //an allowlist of reads, like presence (HAS-239): weather-service serves reads only, and
            //whatever it adds later is published by a decision here, not by a /** tail - which would
            //also forward a dot segment (weather/../presence/clients) as it was sent
            .route("weather", r -> r
                .path("/weather/current", "/weather/status", "/weather/history")
                .and().method(HttpMethod.GET)
                .uri(uri(services.weather())))
            .build();
    }

    private static String uri(final ServiceUri service) {
        return service.uri();
    }
}
