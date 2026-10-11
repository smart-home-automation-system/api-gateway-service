package cloud.cholewa.gateway.routes;

import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

//HAS-212: a GET whose connection to the target broke at once is sent once more, on another
//connection. Reactor Netty repeats a request by itself only while nothing of it has been sent; a
//connection the target has dropped is usually found out by the answer that never comes, after the
//request went out, and that failure used to reach the caller - the dashboards, which poll every 30 s.
//
//A filter of our own instead of the Retry filter of Spring Cloud Gateway: that one switches on the
//caching of request bodies for its route (RetryGatewayFilterFactory.apply calls enableBodyCaching),
//and AdaptCachedBodyGlobalFilter then joins the body of EVERY request to that route in memory,
//without a limit, before routing it - writes included, on the one replica that is the only way into
//the cluster. This filter keeps nothing, and so it can only repeat a request that has no body.
//
//A global filter, so every route has it - also one added later - without anything to remember in
//RoutesConfig. What it repeats, and what not:
//- GET only: a read can be asked twice; a write (POST /heating switches the house) may have been
//  carried out before the connection broke and is never sent again. HEAD and OPTIONS are left out
//  as well - nothing here uses them, and a narrow rule is easier to trust
//- only a GET that announces no body (no Content-Length above zero, no Transfer-Encoding): the body
//  of the caller can be read once. Sent again, such a request would go out with its Content-Length
//  and without its body, on a connection that returns to the pool - and the target would read the
//  next caller's request as the missing body
//- an IOException only: a 5xx is the service's own answer and is passed on as it is, and a
//  response-timeout arrives as a 504 of the gateway (a ResponseStatusException), never repeated
//- only a failure that came at once (QUICK_FAILURE): the response-timeout applies to each attempt,
//  so a read that broke after 25 s would otherwise be run again in full - up to twice the timeout
//  for the caller, and twice on a service whose database pool has two connections
//- once: the second failure is handed on as it is and becomes the 502 of UpstreamFailureFilter
@Component
public class FailedReadRepeatFilter implements GlobalFilter, Ordered {

    //a connection that is dead in the pool fails within milliseconds of the request being written,
    //and so does a refused one. A second leaves room for a pause of the JVM, and stays below the
    //connect-timeout: a target that does not even answer the connect is not waited for twice.
    //ApiGatewayServiceApplicationTest holds the two values against each other
    public static final Duration QUICK_FAILURE = Duration.ofSeconds(1);

    //inside NettyWriteResponseFilter (-1), so nothing is repeated once an answer is being written
    //to the caller, and inside GatewayMetricsFilter (0), which then times the request as a whole
    static final int ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 2;

    private static final Logger log = LoggerFactory.getLogger(FailedReadRepeatFilter.class);

    private final LongSupplier nanoTime;

    public FailedReadRepeatFilter() {
        this(System::nanoTime);
    }

    FailedReadRepeatFilter(final LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (!isReadWithoutBody(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        return Mono.defer(() -> {
            long started = nanoTime.getAsLong();

            return chain.filter(exchange)
                .onErrorResume(
                    failure -> failure instanceof IOException && failedQuickly(started),
                    failure -> repeat(exchange, chain, failure)
                );
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static boolean isReadWithoutBody(final ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();

        return HttpMethod.GET.equals(request.getMethod())
            && headers.getContentLength() <= 0
            && !headers.containsHeader(HttpHeaders.TRANSFER_ENCODING);
    }

    private boolean failedQuickly(final long started) {
        return nanoTime.getAsLong() - started <= QUICK_FAILURE.toNanos();
    }

    private Mono<Void> repeat(final ServerWebExchange exchange, final GatewayFilterChain chain, final Throwable failure) {
        //before the second attempt, not after it succeeded: a repetition that fails as well leaves
        //one ERROR that reads like a single failed call, and the count of these lines would be
        //lowest when things are worst. The type alone - the message names the internal address
        log.warn(
            "Repeating GET on route [{}] after a broken connection [{}]",
            RouteId.of(exchange), failure.getClass().getSimpleName()
        );

        closeObservation(exchange, failure);
        //forgets the failed attempt - most of all the "already routed" mark, with which
        //NettyRoutingFilter would let the second attempt pass without calling the target
        ServerWebExchangeUtils.reset(exchange);

        return chain.filter(exchange);
    }

    //ObservedRequestHttpHeadersFilter starts an observation for every call to a target and keeps
    //only the latest one in the exchange, so the one of the failed attempt is closed here, the way
    //ObservationClosingWebExceptionHandler closes the one of a request that failed for good.
    //No look at that handler's "already stopped" mark: it is set when the headers of an answer
    //arrive, and a failure reaches this filter only before that
    private static void closeObservation(final ServerWebExchange exchange, final Throwable failure) {
        Observation observation =
            (Observation) exchange.getAttributes().remove(ServerWebExchangeUtils.GATEWAY_OBSERVATION_ATTR);

        if (observation != null) {
            observation.error(failure);
            observation.stop();
        }
    }
}
