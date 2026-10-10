package cloud.cholewa.gateway.error;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamFailureFilterTest {

    //the text of the failure of 2026-10-08, as the epoll transport words it on Linux. The test of
    //the whole gateway (UpstreamConnectionFailureTest) gets it from a real reset, but only where
    //the operating system says "by peer" - on Windows the same reset reads "Connection reset"
    private static final String RESET = "recvAddress(..) failed with error(-104): Connection reset by peer";

    private final UpstreamFailureFilter sut = new UpstreamFailureFilter();

    private final MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/home/water/status/temperature"));

    //the reason of the filter: this is the check that made the error handler refuse the exception
    @Test
    void should_turn_a_reset_connection_into_an_error_that_is_not_taken_for_a_lost_client() {
        IOException reset = new IOException(RESET);
        assertThat(DisconnectedClientHelper.isClientDisconnectedException(reset)).isTrue();

        StepVerifier.create(sut.filter(exchange, failingWith(reset)))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(UpstreamUnavailableException.class);
                assertThat(DisconnectedClientHelper.isClientDisconnectedException(error)).isFalse();
            })
            .verify();
    }

    //neither the cause nor its message: both name the internal address, and both would bring the
    //"lost client" verdict back
    @Test
    void should_keep_the_type_of_the_failure_and_nothing_else() {
        IOException reset = new IOException("Connection reset by peer: water-service/10.96.0.7:6200");

        StepVerifier.create(sut.filter(exchange, failingWith(reset)))
            .expectErrorSatisfies(error -> {
                assertThat(((UpstreamUnavailableException) error).failure()).isEqualTo("IOException");
                assertThat(error).hasNoCause();
                assertThat(error.getMessage()).isEqualTo("Upstream service unavailable");
            })
            .verify();
    }

    //Reactor Netty reports a connection closed under a request as an AbortedException, which is
    //not an IOException - and one of the class names Spring takes for a lost client
    @Test
    void should_turn_an_aborted_connection_into_an_upstream_failure() {
        StepVerifier.create(sut.filter(exchange, failingWith(AbortedException.beforeSend())))
            .expectErrorSatisfies(error ->
                assertThat(((UpstreamUnavailableException) error).failure()).isEqualTo("AbortedException"))
            .verify();
    }

    @Test
    void should_turn_a_refused_connection_into_an_upstream_failure() {
        StepVerifier.create(sut.filter(exchange, failingWith(new ConnectException("Connection refused"))))
            .expectError(UpstreamUnavailableException.class)
            .verify();
    }

    //a response-timeout arrives as a 504 of the gateway itself, an unexpected error as whatever it is
    @Test
    void should_leave_other_errors_alone() {
        ResponseStatusException timeout = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT);
        IllegalStateException unexpected = new IllegalStateException("unexpected");

        StepVerifier.create(sut.filter(exchange, failingWith(timeout))).expectErrorMatches(timeout::equals).verify();
        StepVerifier.create(sut.filter(exchange, failingWith(unexpected))).expectErrorMatches(unexpected::equals).verify();
    }

    //once the answer is being sent, a broken pipe may really be the caller hanging up, and no error
    //can be rendered any more: Spring has to see the original to keep that quiet
    @Test
    void should_leave_an_error_alone_once_the_response_is_committed() {
        IOException brokenPipe = new IOException("Broken pipe");

        exchange.getResponse().setComplete().block();

        StepVerifier.create(sut.filter(exchange, failingWith(brokenPipe)))
            .expectErrorMatches(brokenPipe::equals)
            .verify();
    }

    @Test
    void should_pass_an_answered_exchange_through() {
        StepVerifier.create(sut.filter(exchange, ignored -> Mono.empty())).verifyComplete();
    }

    //outside the filter that reads the body of the target's answer, or a reset between the headers
    //and the body would pass it by
    @Test
    void should_run_outside_the_filter_that_writes_the_response() {
        assertThat(sut.getOrder()).isLessThan(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER);
    }

    private static GatewayFilterChain failingWith(final Throwable throwable) {
        return ignored -> Mono.error(throwable);
    }
}
