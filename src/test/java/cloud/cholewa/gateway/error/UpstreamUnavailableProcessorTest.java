package cloud.cholewa.gateway.error;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
}
