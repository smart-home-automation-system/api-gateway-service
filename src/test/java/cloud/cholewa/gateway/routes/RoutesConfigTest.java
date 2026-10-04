package cloud.cholewa.gateway.routes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
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
            "presence", "http://presence-service:6200",
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
        assertThat(matchedRouteFor("/home/household")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/household/member/Test/device")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/householdx")).isNull();
        assertThat(matchedRouteFor("/home/presence/residents/presence")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna/report")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna%20Maria/report")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/amx")).isEqualTo("amx");
        assertThat(matchedRouteFor("/home/ai")).isEqualTo("ai");
        assertThat(matchedRouteFor("/home/nothing")).isNull();
    }

    //the diagnostic endpoint of presence-service answers the MAC address of every device on the home
    //network - it has to stay unreachable from outside, whatever else of that service is routed.
    //The route is an allowlist of two reads, so nothing else of the service matches: not the
    //endpoint itself, not a path that reaches it through a dot segment, not another method
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/presence",
        "/home/presence/clients",
        "/home/presence/clients/",
        "/home/presence/clients/x",
        "/home/presence/residents",
        "/home/presence/residentsx",
        "/home/presence/residents/../clients",
        "/home/presence/residents/..%2fclients",
        "/home/presence/residents/Anna",
        "/home/presence/residents/Anna/devices",
        "/home/presence/residents/Anna/report/x"
    })
    void should_route_nothing_of_presence_service_but_the_reporting_api(final String path) {
        assertThat(matchedRouteFor(path)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void should_route_only_reads_to_presence_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/residents/presence")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/residents/Anna/report")).isNull();
    }

    private String matchedRouteFor(final String path) {
        return matchedRouteFor(HttpMethod.GET, path);
    }

    private String matchedRouteFor(final HttpMethod method, final String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.method(method, URI.create(path)));

        return routeLocator.getRoutes()
            .filterWhen(route -> route.getPredicate().apply(exchange))
            .next()
            .map(Route::getId)
            .block();
    }
}
