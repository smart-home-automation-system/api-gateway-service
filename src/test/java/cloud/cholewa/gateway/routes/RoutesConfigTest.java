package cloud.cholewa.gateway.routes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoutesConfigTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void should_expose_one_route_per_internal_service() {
        Map<String, String> targets = routeLocator.getRoutes()
            .collectList()
            .block()
            .stream()
            .collect(Collectors.toMap(Route::getId, route -> route.getUri().toString()));

        assertThat(targets).containsExactlyInAnyOrderEntriesOf(Map.of(
            "ai", "http://ai-service:6200",
            "amx", "http://amx-service:6200",
            "boiler", "http://boiler-service:6200",
            "database", "http://database-service:6200",
            "heating", "http://heating-service:6200",
            "water", "http://water-service:6200"
        ));
    }

    //the predicates are written without /home, but PathRoutePredicateFactory prepends
    //spring.webflux.base-path to every pattern and matches it against the full request path - so the
    //paths asserted here are the external ones the ingress forwards, prefix included
    @Test
    void should_match_the_paths_the_ingress_forwards() {
        assertThat(matchedRouteFor("/home/heating/status/active")).isEqualTo("heating");
        assertThat(matchedRouteFor("/home/heating")).isEqualTo("heating");
        assertThat(matchedRouteFor("/home/water/status/temperature")).isEqualTo("water");
        assertThat(matchedRouteFor("/home/boiler/status")).isEqualTo("boiler");
        assertThat(matchedRouteFor("/home/device/configuration/eaton")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/amx")).isEqualTo("amx");
        assertThat(matchedRouteFor("/home/ai")).isEqualTo("ai");
        assertThat(matchedRouteFor("/home/nothing")).isNull();
    }

    private String matchedRouteFor(final String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));

        return routeLocator.getRoutes()
            .filterWhen(route -> route.getPredicate().apply(exchange))
            .next()
            .map(Route::getId)
            .block();
    }
}
