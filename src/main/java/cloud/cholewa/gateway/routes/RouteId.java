package cloud.cholewa.gateway.routes;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;

//the id a route has in RoutesConfig ("water"), for a log line: it says which service a call was
//for without naming a host or an address
public final class RouteId {

    private static final String UNKNOWN = "unknown";

    private RouteId() {
    }

    public static String of(final ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        return route != null ? route.getId() : UNKNOWN;
    }
}
