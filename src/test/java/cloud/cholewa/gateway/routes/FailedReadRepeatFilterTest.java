package cloud.cholewa.gateway.routes;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FailedReadRepeatFilterTest {

    private static final String PATH = "/home/water/status/temperature";

    //the clock of the filter, moved by hand: how long an attempt took is what the tests decide
    private final AtomicLong nanoTime = new AtomicLong();
    private final FailedReadRepeatFilter sut = new FailedReadRepeatFilter(nanoTime::get);

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FailedReadRepeatFilter.class);

    @BeforeEach
    void captureLog() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(log);
    }

    @Test
    void should_repeat_a_get_whose_connection_broke_at_once() {
        Attempts attempts = new Attempts(new IOException("Connection reset by peer"));

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts)).verifyComplete();

        assertThat(attempts.count()).isEqualTo(2);
    }

    //the only trace of a failure the caller never sees - and WARN is the level at which it shows
    //without raising the alert on errors
    @Test
    void should_log_a_repetition_at_warn_with_its_route() {
        Attempts attempts = new Attempts(new IOException("Connection reset by peer: water-service/10.96.0.7:6200"));

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts)).verifyComplete();

        assertThat(log.list).hasSize(1);
        assertThat(log.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(log.list.getFirst().getFormattedMessage())
            .isEqualTo("Repeating GET on route [water] after a broken connection [IOException]");
    }

    //the second failure goes on as it is: UpstreamFailureFilter makes the 502 of it
    @Test
    void should_repeat_once_and_hand_the_second_failure_on() {
        IOException second = new ConnectException("Connection refused");
        Attempts attempts = new Attempts(new IOException("Connection reset by peer"), second, second);

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts))
            .expectErrorMatches(second::equals)
            .verify();

        assertThat(attempts.count()).isEqualTo(2);
        //the line is written before the second attempt: a repetition that fails is counted too
        assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
    }

    //the body of the caller can be read once: a GET that announces one would be sent again with its
    //Content-Length and without its body, and the target would read the next request on that
    //connection as the missing bytes
    @Test
    void should_not_repeat_a_get_that_announces_a_body_by_its_length() {
        IOException reset = new IOException("Connection reset by peer");
        Attempts attempts = new Attempts(reset);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(PATH).header("Content-Length", "5"));

        StepVerifier.create(sut.filter(exchange, attempts)).expectErrorMatches(reset::equals).verify();

        assertThat(attempts.count()).isEqualTo(1);
        assertThat(log.list).isEmpty();
    }

    @Test
    void should_not_repeat_a_get_that_announces_a_body_by_its_transfer_encoding() {
        IOException reset = new IOException("Connection reset by peer");
        Attempts attempts = new Attempts(reset);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(PATH).header("Transfer-Encoding", "chunked"));

        StepVerifier.create(sut.filter(exchange, attempts)).expectErrorMatches(reset::equals).verify();

        assertThat(attempts.count()).isEqualTo(1);
    }

    //a length of zero announces nothing: some clients send it with every request
    @Test
    void should_repeat_a_get_with_a_content_length_of_zero() {
        Attempts attempts = new Attempts(new IOException("Connection reset by peer"));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(PATH).header("Content-Length", "0"));

        StepVerifier.create(sut.filter(exchange, attempts)).verifyComplete();

        assertThat(attempts.count()).isEqualTo(2);
    }

    //a write may have been carried out before the connection broke; HEAD and OPTIONS are simply
    //not part of the rule
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void should_never_repeat_anything_but_a_get(final String method) {
        IOException reset = new IOException("Connection reset by peer");
        Attempts attempts = new Attempts(reset);

        StepVerifier.create(sut.filter(exchange(HttpMethod.valueOf(method)), attempts))
            .expectErrorMatches(reset::equals)
            .verify();

        assertThat(attempts.count()).isEqualTo(1);
    }

    //the response-timeout applies to each attempt: a read that broke after seconds of waiting
    //would be run again in full. Both sides of the bound
    @Test
    void should_repeat_a_get_that_failed_exactly_at_the_bound() {
        Attempts attempts = new Attempts(new IOException("Connection reset by peer"))
            .taking(FailedReadRepeatFilter.QUICK_FAILURE);

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts)).verifyComplete();

        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void should_not_repeat_a_get_that_failed_later_than_the_bound() {
        IOException reset = new IOException("Connection reset by peer");
        Attempts attempts = new Attempts(reset).taking(FailedReadRepeatFilter.QUICK_FAILURE.plusNanos(1));

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts))
            .expectErrorMatches(reset::equals)
            .verify();

        assertThat(attempts.count()).isEqualTo(1);
    }

    //a response-timeout arrives as the 504 of the gateway, with the TimeoutException as its cause;
    //anything unexpected is not a broken connection either
    @Test
    void should_not_repeat_a_response_timeout_or_an_unexpected_error() {
        ResponseStatusException timeout =
            new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer", new TimeoutException());
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        Attempts timedOut = new Attempts(timeout);
        Attempts failed = new Attempts(unexpected);

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), timedOut)).expectErrorMatches(timeout::equals).verify();
        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), failed)).expectErrorMatches(unexpected::equals).verify();

        assertThat(timedOut.count()).isEqualTo(1);
        assertThat(failed.count()).isEqualTo(1);
    }

    //an answer of the service, whatever its status, is no error of the chain: nothing to repeat
    @Test
    void should_call_the_target_once_when_it_answers() {
        Attempts attempts = new Attempts();

        StepVerifier.create(sut.filter(exchange(HttpMethod.GET), attempts)).verifyComplete();

        assertThat(attempts.count()).isEqualTo(1);
        assertThat(log.list).isEmpty();
    }

    //NettyRoutingFilter lets an exchange marked as routed pass without calling the target
    @Test
    void should_forget_the_failed_attempt_before_the_second_one() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET);
        List<Boolean> alreadyRouted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        GatewayFilterChain chain = ignored -> Mono.defer(() -> {
            alreadyRouted.add(ServerWebExchangeUtils.isAlreadyRouted(exchange));
            ServerWebExchangeUtils.setAlreadyRouted(exchange);
            return calls.incrementAndGet() == 1 ? Mono.error(new IOException("Connection reset by peer")) : Mono.empty();
        });

        StepVerifier.create(sut.filter(exchange, chain)).verifyComplete();

        assertThat(alreadyRouted).containsExactly(false, false);
    }

    //every call to a target starts an observation and the exchange keeps only the latest: the one
    //of the failed attempt has to be stopped before the second attempt replaces it
    @Test
    void should_stop_the_observation_of_the_failed_attempt() {
        List<String> events = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onError(final Observation.Context context) {
                events.add("error " + context.getError().getClass().getSimpleName());
            }

            @Override
            public void onStop(final Observation.Context context) {
                events.add("stop " + context.getName());
            }

            @Override
            public boolean supportsContext(final Observation.Context context) {
                return true;
            }
        });

        MockServerWebExchange exchange = exchange(HttpMethod.GET);
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> observationLeft = new ArrayList<>();

        GatewayFilterChain chain = ignored -> Mono.defer(() -> {
            int call = calls.incrementAndGet();
            observationLeft.add(exchange.getAttributes().containsKey(ServerWebExchangeUtils.GATEWAY_OBSERVATION_ATTR));
            exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_OBSERVATION_ATTR,
                Observation.start("attempt-" + call, registry)
            );
            return call == 1 ? Mono.error(new IOException("Connection reset by peer")) : Mono.empty();
        });

        StepVerifier.create(sut.filter(exchange, chain)).verifyComplete();

        //the first one is closed with its error; the second is left to the filters that close the
        //observation of an answered call
        assertThat(events).containsExactly("error IOException", "stop attempt-1");
        assertThat(observationLeft).containsExactly(false, false);
    }

    //inside the filter that writes the answer to the caller, so nothing is repeated once that
    //began. Its place against the metrics filter is asserted on the beans, in
    //ApiGatewayServiceApplicationTest
    @Test
    void should_run_inside_the_filter_that_writes_the_response() {
        assertThat(sut.getOrder()).isGreaterThan(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER);
    }

    private static MockServerWebExchange exchange(final HttpMethod method) {
        return exchange(MockServerHttpRequest.method(method, PATH));
    }

    private static MockServerWebExchange exchange(final MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        exchange.getAttributes().put(
            ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async().id("water").uri("http://water-service:6200").predicate(ignored -> true).build()
        );

        return exchange;
    }

    //a chain that fails its attempts with the given errors, in order, and answers once they are used
    //up; each attempt can be made to take a time on the clock of the filter
    private final class Attempts implements GatewayFilterChain {

        private final List<Throwable> failures;
        private final AtomicInteger count = new AtomicInteger();
        private Duration duration = Duration.ZERO;

        private Attempts(final Throwable... failures) {
            this.failures = List.of(failures);
        }

        private Attempts taking(final Duration time) {
            this.duration = time;
            return this;
        }

        private int count() {
            return count.get();
        }

        @Override
        public Mono<Void> filter(final ServerWebExchange exchange) {
            return Mono.defer(() -> {
                int attempt = count.getAndIncrement();
                nanoTime.addAndGet(duration.toNanos());

                return attempt < failures.size() ? Mono.error(failures.get(attempt)) : Mono.empty();
            });
        }
    }
}
