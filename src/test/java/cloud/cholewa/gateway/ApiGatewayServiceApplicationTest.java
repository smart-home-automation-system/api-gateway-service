package cloud.cholewa.gateway;

import cloud.cholewa.gateway.routes.FailedReadRepeatFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.GatewayMetricsFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ApiGatewayServiceApplicationTest {

    @Autowired
    private HttpClientProperties httpClientProperties;

    @Autowired
    private FailedReadRepeatFilter failedReadRepeatFilter;

    @Autowired
    private GatewayMetricsFilter gatewayMetricsFilter;

    @Autowired
    private NettyWriteResponseFilter nettyWriteResponseFilter;

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

    //a read is repeated only when its attempt failed within FailedReadRepeatFilter.QUICK_FAILURE.
    //That bound has to stay below the connect-timeout the gateway really runs with, or a target
    //that does not answer the connect is waited for twice - so the two values are held against
    //each other here, where a change of either one shows
    @Test
    void should_give_up_on_a_connect_later_than_a_read_is_still_repeated() {
        Duration connectTimeout = Duration.ofMillis(httpClientProperties.getConnectTimeout());

        assertThat(connectTimeout).isEqualTo(Duration.ofSeconds(2));
        assertThat(FailedReadRepeatFilter.QUICK_FAILURE).isLessThan(connectTimeout);
    }

    //the repetition of a read happens before anything is written to the caller, and the metrics of
    //the gateway time a repeated request as one - both by the order of the filters as they are
    //registered, not by a constant of ours
    @Test
    void should_repeat_a_read_inside_the_filters_that_write_the_response_and_time_the_request() {
        assertThat(failedReadRepeatFilter.getOrder())
            .isGreaterThan(nettyWriteResponseFilter.getOrder())
            .isGreaterThan(gatewayMetricsFilter.getOrder());
    }
}
