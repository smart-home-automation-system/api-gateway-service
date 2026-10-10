package cloud.cholewa.gateway.error;

import java.io.IOException;

//what UpstreamFailureFilter turns a broken connection to a target service into. It deliberately
//carries neither the original exception as its cause nor the original message: Spring decides
//"the client has gone away" by the class names of the whole cause chain and by the text of the
//innermost message, so a wrapped "Connection reset by peer" would still be refused by the error
//handler - and the message names the internal host, pod IP and port, which must not leave the
//cluster. Only the type of the failure and the id of the route are kept, for the log
public class UpstreamUnavailableException extends IOException {

    private final String failure;
    private final String route;

    public UpstreamUnavailableException(final Throwable original, final String route) {
        super("Upstream service unavailable");
        this.failure = original.getClass().getSimpleName();
        this.route = route;
    }

    public String failure() {
        return failure;
    }

    //the id of the route in RoutesConfig ("water") - a name of this repository, not an address
    public String route() {
        return route;
    }
}
