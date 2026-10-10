package cloud.cholewa.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApiGatewayServiceApplicationTest {

    @Autowired
    private HttpClientProperties httpClientProperties;

    @Test
    void contextLoads() {
    }

    //HAS-212: an idle connection has to leave the pool long before the 24 h after which the node
    //forgets it (see application.yaml). Left out, each of these falls back to "no limit" without a
    //word - and the failure it prevents shows weeks later, on a connection nobody remembers
    @Test
    void should_limit_the_idle_time_and_the_life_of_a_pooled_connection() {
        HttpClientProperties.Pool pool = httpClientProperties.getPool();

        assertThat(pool.getMaxIdleTime()).isEqualTo(Duration.ofMinutes(2));
        assertThat(pool.getMaxLifeTime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(pool.getEvictionInterval()).isEqualTo(Duration.ofSeconds(30));
    }
}
