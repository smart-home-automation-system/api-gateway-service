package cloud.cholewa.gateway.error;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.Collections;

//mapped for IOException, not just ConnectException: a missing Service object surfaces as
//UnknownHostException and a dropped connection as PrematureCloseException, both of which would
//otherwise fall back to DefaultExceptionProcessor and answer 500 with the internal name inside.
//The gateway is the only service reachable from outside, so what it puts in "details" leaves the
//cluster - and since the cluster logs are JSON, it is stored and searchable as well. The default
//processor would pass the connection error through verbatim, which names the internal host, pod IP
//and port of the target; only the exception type is logged and the caller gets a fixed message
public class UpstreamUnavailableProcessor implements ExceptionProcessor {

    private static final Logger log = LoggerFactory.getLogger(UpstreamUnavailableProcessor.class);

    @Override
    public Errors apply(final Throwable throwable) {

        log.error("Upstream service unreachable [{}]", throwable.getClass().getSimpleName());

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_GATEWAY)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Upstream service unavailable")
                    .build()
            ))
            .build();
    }
}
