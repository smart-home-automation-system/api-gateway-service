package cloud.cholewa.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ServiceUri(
    @NotBlank String scheme,
    @NotBlank String host,
    @Positive int port
) {
    public String uri() {
        return scheme + "://" + host + ":" + port;
    }
}
