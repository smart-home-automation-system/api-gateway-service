package cloud.cholewa.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

//k8s DNS names in the home profile, localhost with each service's local port in the local one.
//This replaces the Eureka discovery locator removed in HAS-170: nothing registers anywhere now,
//so every target is spelled out here
//@Validated so a missing internal.service.<name> block fails at startup naming the key, instead of
//binding to null and throwing a bare NPE while the route table is built
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesConfig(
    @NotNull @Valid ServiceUri ai,
    @NotNull @Valid ServiceUri amx,
    @NotNull @Valid ServiceUri boiler,
    @NotNull @Valid ServiceUri database,
    @NotNull @Valid ServiceUri heating,
    @NotNull @Valid ServiceUri water
) {
}
