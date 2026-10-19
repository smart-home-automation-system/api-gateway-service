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
            "blinds", "http://blinds-service:6200",
            "boiler", "http://boiler-service:6200",
            "database", "http://database-service:6200",
            "heating", "http://heating-service:6200",
            "light", "http://light-service:6200",
            "presence", "http://presence-service:6200",
            "water", "http://water-service:6200",
            "weather", "http://weather-service:6200"
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
        assertThat(matchedRouteFor("/home/device/registry")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/device/registry/7")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/device/registry/7/enable")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/calendar/days")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/calendar/days-off/2026-05-04")).isEqualTo("database");
        assertThat(matchedRouteFor("/home/presence/residents/presence")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna/report")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna%20Maria/report")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna/report/daily")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/residents/Anna%20Maria/report/daily")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/presence/house/report")).isEqualTo("presence");
        assertThat(matchedRouteFor("/home/amx")).isEqualTo("amx");
        assertThat(matchedRouteFor("/home/ai")).isEqualTo("ai");
        assertThat(matchedRouteFor("/home/weather/current")).isEqualTo("weather");
        assertThat(matchedRouteFor("/home/weather/status")).isEqualTo("weather");
        assertThat(matchedRouteFor("/home/weather/history")).isEqualTo("weather");
        assertThat(matchedRouteFor("/home/blinds")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blinds/rooms/office")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blinds/52")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blinds/events/gen1?device=52&event=open")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blinds/schedules")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blinds/schedules/3/today")).isEqualTo("blinds");
        assertThat(matchedRouteFor("/home/blindsx")).isNull();
        assertThat(matchedRouteFor("/home/light")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/light/rooms/office")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/light/rooms/office/occupancy")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/light/45")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/light/events/gen1?device=90&event=on")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/light/events/gen1?device=97&event=motion&lux=10")).isEqualTo("light");
        assertThat(matchedRouteFor("/home/lightx")).isNull();
        assertThat(matchedRouteFor("/home/nothing")).isNull();
    }

    //blinds-service is commanded and its schedules are written through the gateway - by path alone,
    //whatever the method
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void should_route_the_writes_of_blinds_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/52")).isEqualTo("blinds");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/52/position")).isEqualTo("blinds");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/rooms/office")).isEqualTo("blinds");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/schedules")).isEqualTo("blinds");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/schedules/3")).isEqualTo("blinds");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/blinds/schedules/3/enable")).isEqualTo("blinds");
    }

    //light-service is commanded through the gateway - by path alone, whatever the method (HAS-265)
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void should_route_the_writes_of_light_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/light/45")).isEqualTo("light");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/light/45/brightness")).isEqualTo("light");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/light/89/color")).isEqualTo("light");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/light/rooms/office")).isEqualTo("light");
    }

    //the registry and the calendar are written through the gateway as well - the route of
    //database-service is by path alone, whatever the method
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void should_route_the_writes_of_database_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/device/registry")).isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/device/registry/7")).isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/device/registry/7/enable"))
            .isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/device/registry/7/disable"))
            .isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/calendar/days-off/2026-05-04"))
            .isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/device/configuration/eaton"))
            .isEqualTo("database");
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/household/member/Test")).isEqualTo("database");
    }

    //The tails are open, and the pattern is matched against the path as it was sent: whatever
    //lies below a routed path goes to database-service unchanged, a dot segment included. That
    //service matches the same raw path and knows no such endpoint - and has none to hide
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/calendar",
        "/home/calendar/",
        "/home/calendar/anything/below",
        "/home/device/registry/",
        "/home/device/registry/7/anything/below",
        "/home/device/registry/../other"
    })
    void should_route_whatever_lies_below_a_path_of_database_service(final String path) {
        assertThat(matchedRouteFor(path)).isEqualTo("database");
    }

    //the tail of blinds is open like the one of database-service: the bare path with a slash and a
    //dot segment below the service go to blinds-service as they were sent, which knows no such path
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/blinds/",
        "/home/blinds/anything/below",
        "/home/blinds/../other",
        "/home/blinds/events/gen1/"
    })
    void should_route_whatever_lies_below_the_path_of_blinds_service(final String path) {
        assertThat(matchedRouteFor(path)).isEqualTo("blinds");
    }

    //the tail of light is open like the one of blinds: what lies below goes to light-service as it was sent
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/light/",
        "/home/light/anything/below",
        "/home/light/../other",
        "/home/light/events/gen1/"
    })
    void should_route_whatever_lies_below_the_path_of_light_service(final String path) {
        assertThat(matchedRouteFor(path)).isEqualTo("light");
    }

    //the route of database-service names the branches of /device it publishes: a path next to
    //them, or one that only begins like them, belongs to nobody
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/device",
        "/home/device/",
        "/home/device/registryx",
        "/home/device/registry-old/7",
        "/home/device/configurationx/eaton",
        "/home/device/other",
        "/home/calendarx",
        "/home/calendarx/days",
        "/home/calendars/days",
        "/home/registry",
        "/home/days"
    })
    void should_route_nothing_next_to_the_paths_of_database_service(final String path) {
        assertThat(matchedRouteFor(path)).isNull();
    }

    //the reads of weather-service and nothing else: no other path of that service, no tail, no dot
    //segment that would be forwarded as it was sent (weather/../presence/clients)
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/weather",
        "/home/weather/",
        "/home/weather/currentx",
        "/home/weather/current/",
        "/home/weather/current/x",
        "/home/weather/history/x",
        "/home/weather/../presence/clients",
        "/home/weather/current/../../presence/clients",
        "/home/weather/..%2fpresence/clients",
        "/home/weather/actuator/health"
    })
    void should_route_nothing_of_weather_service_but_its_reads(final String path) {
        assertThat(matchedRouteFor(path)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void should_route_only_reads_to_weather_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/weather/current")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/weather/status")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/weather/history")).isNull();
    }

    //the diagnostic endpoint of presence-service answers the MAC address of every device on the home
    //network - it has to stay unreachable from outside, whatever else of that service is routed.
    //The route is an allowlist of four reads, so nothing else of the service matches: not the
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
        "/home/presence/residents/Anna/report/x",
        "/home/presence/residents/Anna/report/daily/x",
        "/home/presence/house",
        "/home/presence/house/report/x",
        "/home/presence/house/../clients",
        "/home/presence/house/report/",
        "/home/presence/residents/Anna/report/daily/",
        "/home/presence/residents/presence/"
    })
    void should_route_nothing_of_presence_service_but_the_reporting_api(final String path) {
        assertThat(matchedRouteFor(path)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void should_route_only_reads_to_presence_service(final String method) {
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/residents/presence")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/residents/Anna/report")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/residents/Anna/report/daily")).isNull();
        assertThat(matchedRouteFor(HttpMethod.valueOf(method), "/home/presence/house/report")).isNull();
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
