package cloud.cholewa.gateway.routes;

import cloud.cholewa.gateway.config.InternalServicesConfig;
import cloud.cholewa.gateway.config.ServiceUri;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.io.IOException;

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
                .filters(RoutesConfig::repeatFailedRead)
                .metadata(RESPONSE_TIMEOUT_ATTR, 120000L)
                .uri(uri(services.ai())))
            .route("amx", r -> r.path("/amx", "/amx/**")
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.amx())))
            .route("boiler", r -> r.path("/boiler", "/boiler/**")
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.boiler())))
            //the Eaton device configuration and the household registry - both live in database-service
            .route("database", r -> r.path("/device/configuration/**", "/household", "/household/**")
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.database())))
            .route("heating", r -> r.path("/heating", "/heating/**")
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.heating())))
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
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.presence())))
            .route("water", r -> r.path("/water", "/water/**")
                .filters(RoutesConfig::repeatFailedRead)
                .uri(uri(services.water())))
            .build();
    }

    //HAS-212: a GET whose connection to the target broke is sent once more, on another connection.
    //Reactor Netty repeats a request by itself only while nothing of it has been sent; a connection
    //the target has dropped is usually found out by the answer that never comes, after the request
    //went out, and that failure used to reach the caller - the dashboards, which poll every 30 s.
    //On every route, so that a read added to a service later is covered without anyone deciding it:
    //- GET only: a read can be asked twice, a write (POST /heating switches the house) may have been
    //  carried out before the connection broke and must not be sent again
    //- one repetition and no backoff: against a target that is really gone a second failure is the
    //  answer (502), and it costs a second connect-timeout at most
    //- IOException only, no status: a 5xx is the service's own answer and is passed on as it is, and
    //  a response-timeout (not an IOException) is not waited for twice
    private static UriSpec repeatFailedRead(final GatewayFilterSpec filters) {
        return filters.retry(retry -> retry
            .setRetries(1)
            .setMethods(HttpMethod.GET)
            .setSeries()
            .setExceptions(IOException.class));
    }

    private static String uri(final ServiceUri service) {
        return service.uri();
    }
}
