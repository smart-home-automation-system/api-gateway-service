package cloud.cholewa.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal.service.water.uri")
public record HostWaterHeatingConfig(
    String scheme,
    String host,
    String port
) {
}
