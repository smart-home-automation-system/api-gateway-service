package cloud.cholewa.gateway.error;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Mono;

import java.io.IOException;

//HAS-212: a connection to a target service that is reset fails with "Connection reset by peer",
//and Spring reads exactly that text (and "Broken pipe", and a few exception names) as "the client
//of this server has gone away": AbstractErrorWebExceptionHandler then refuses to render the error,
//HttpWebHandlerAdapter sets a bare 500 and the caller gets a status without a body - outside the
//error contract, and nowhere near UpstreamUnavailableProcessor. A server cannot tell the two apart
//by the exception; a gateway can, by where the error comes from. Everything inside this filter is
//the exchange with the target service (sending the request, waiting for the answer, reading its
//body), so a broken connection seen here while nothing has been sent to the caller yet is the
//target's, and it is handed on as an exception the error handler does render: 502 with a body
@Component
public class UpstreamFailureFilter implements GlobalFilter, Ordered {

    //just outside NettyWriteResponseFilter, which reads the body of the answer: a connection reset
    //between the headers and the body of an answer is caught as well. The Retry filter of a route
    //sits further in and still sees the original exception
    static final int ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        return chain.filter(exchange)
            .onErrorMap(
                throwable -> isBrokenConnection(throwable) && !exchange.getResponse().isCommitted(),
                UpstreamUnavailableException::new
            );
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    //once the answer is on its way to the caller nothing can be rendered any more, and an error of
    //that phase may really be the caller hanging up - it is left as it is, for Spring to judge
    private static boolean isBrokenConnection(final Throwable throwable) {
        return !(throwable instanceof UpstreamUnavailableException)
            && (throwable instanceof IOException || DisconnectedClientHelper.isClientDisconnectedException(throwable));
    }
}
