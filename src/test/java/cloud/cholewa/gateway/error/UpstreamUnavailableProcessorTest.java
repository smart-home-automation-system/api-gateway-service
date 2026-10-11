package cloud.cholewa.gateway.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamUnavailableProcessorTest {

    private final UpstreamUnavailableProcessor sut = new UpstreamUnavailableProcessor();

    @Test
    void should_answer_with_bad_gateway() {
        Errors errors = sut.apply(new ConnectException("Connection refused: getsockopt: water-service/10.96.0.7:6200"));

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errors.getErrors()).hasSize(1);
    }

    //the gateway is the only service reachable from outside, so the internal host, pod IP and port
    //the connection error carries must not reach the caller - nor the JSON cluster logs
    @Test
    void should_not_leak_the_upstream_address() {
        Errors errors = sut.apply(new ConnectException("Connection refused: getsockopt: water-service/10.96.0.7:6200"));

        ErrorMessage error = errors.getErrors().iterator().next();

        assertThat(error.getMessage()).isEqualTo("Upstream service unavailable");
        assertThat(error.getDetails()).isNull();
    }

    //a missing Service object surfaces as UnknownHostException, which is not a ConnectException -
    //the processor is registered for IOException so this lands here instead of on the default 500,
    //which would have answered with "failed to resolve 'boiler-service'" as details
    @Test
    void should_handle_an_unresolvable_service_name() {
        Errors errors = sut.apply(new UnknownHostException("failed to resolve 'boiler-service'"));

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errors.getErrors().iterator().next().getDetails()).isNull();
    }

    //a broken connection arrives wrapped by UpstreamFailureFilter: the answer is the same 502, and
    //the log line - at ERROR, the level the alerts look at - says what failed and on which route,
    //without the address the original message carried
    @Test
    void should_handle_a_connection_the_filter_reported_as_broken() {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(UpstreamUnavailableProcessor.class);
        log.start();
        logger.addAppender(log);

        Errors errors = sut.apply(new UpstreamUnavailableException(
            new IOException("Connection reset by peer: water-service/10.96.0.7:6200"), "water"
        ));
        logger.detachAppender(log);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errors.getErrors().iterator().next().getMessage()).isEqualTo("Upstream service unavailable");
        assertThat(errors.getErrors().iterator().next().getDetails()).isNull();

        assertThat(log.list).hasSize(1);
        assertThat(log.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(log.list.getFirst().getFormattedMessage())
            .isEqualTo("Upstream service unreachable [IOException] on route [water]");
    }
}
