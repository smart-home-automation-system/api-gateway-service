package cloud.cholewa.gateway.upstream;

import cloud.cholewa.gateway.upstream.StubUpstream.Exchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

//HAS-212: the limits of the pool are plain configuration of a Spring Cloud starter that is pinned
//by hand, outside Spring's compatibility matrix - so this proves they reach the HTTP client, with
//the idle limit shortened to what a test can wait for. The values of production are pinned by
//ApiGatewayServiceApplicationTest
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "management.server.port=0",
        "internal.service.water.host=localhost",
        "spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time=300ms",
        "spring.cloud.gateway.server.webflux.httpclient.pool.eviction-interval=100ms"
    }
)
@ActiveProfiles("test")
class IdleConnectionEvictionTest {

    private static final StubUpstream UPSTREAM = new StubUpstream();
    private static final String PATH = "/home/water/status/temperature";

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void upstream(final DynamicPropertyRegistry registry) {
        registry.add("internal.service.water.port", UPSTREAM::port);
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        UPSTREAM.close();
    }

    @Test
    void should_close_a_connection_left_idle_and_open_another_for_the_next_request() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        client.get().uri(PATH).exchange().expectStatus().isOk();

        //nobody asks for a connection here: it is the sweep in the background that closes it
        await().atMost(Duration.ofSeconds(10)).until(() -> UPSTREAM.connectionsClosedByPeer() == 1);

        client.get().uri(PATH).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.exchanges())
            .extracting(Exchange::connection, Exchange::requestOnConnection)
            .containsExactly(tuple(1, 1), tuple(2, 1));
    }
}
