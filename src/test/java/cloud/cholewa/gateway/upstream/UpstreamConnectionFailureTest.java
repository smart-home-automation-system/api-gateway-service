package cloud.cholewa.gateway.upstream;

import cloud.cholewa.gateway.upstream.StubUpstream.Exchange;
import cloud.cholewa.gateway.upstream.StubUpstream.Failure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//HAS-212: the gateway once answered a GET with 500 and no body, because the connection it took
//from its pool had been reset on the way to the service. Here the gateway runs on a real port and
//its water route points at a stub that resets a connection the gateway keeps alive
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "internal.service.water.host=localhost"}
)
@ActiveProfiles("test")
class UpstreamConnectionFailureTest {

    private static final StubUpstream UPSTREAM = new StubUpstream();
    private static final String PATH = "/home/water/status/temperature";

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void upstream(final DynamicPropertyRegistry registry) {
        registry.add("internal.service.water.port", UPSTREAM::port);
    }

    @AfterAll
    static void stopUpstream() throws IOException {
        UPSTREAM.close();
    }

    //every test starts with one answered call, so the gateway holds a connection to the stub in its
    //pool and the request that follows travels on a connection that has been used before
    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();

        UPSTREAM.reset();
        client.get().uri(PATH).exchange().expectStatus().isOk();
        UPSTREAM.reset();
    }

    @Test
    void should_repeat_a_read_whose_pooled_connection_was_reset() {
        UPSTREAM.failNext(1, Failure.RESET_BEFORE_RESPONSE);

        client.get().uri(PATH)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo(StubUpstream.BODY);

        List<Exchange> exchanges = UPSTREAM.exchanges();

        assertThat(exchanges).extracting(Exchange::method).containsExactly("GET", "GET");
        //the reset request was not the first one on its connection: it came out of the pool
        assertThat(exchanges.getFirst().requestOnConnection()).isGreaterThan(1);
        //and the repeated one went out on another connection
        assertThat(exchanges.getLast().connection()).isNotEqualTo(exchanges.getFirst().connection());
    }

    //one repetition, not a loop: a service that resets every connection is told to the caller
    @Test
    void should_answer_bad_gateway_when_the_repeated_read_fails_as_well() {
        UPSTREAM.failNext(5, Failure.RESET_BEFORE_RESPONSE);

        expectUpstreamUnavailable(client.get().uri(PATH).exchange());

        assertThat(UPSTREAM.exchanges()).extracting(Exchange::method).containsExactly("GET", "GET");
    }

    //a write may have been carried out by the service before the connection broke - the switch of
    //the heating is a POST - so it is sent once and the failure is the caller's to judge
    @Test
    void should_not_repeat_a_write_whose_pooled_connection_was_reset() {
        UPSTREAM.failNext(1, Failure.RESET_BEFORE_RESPONSE);

        expectUpstreamUnavailable(
            client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
        );

        assertThat(UPSTREAM.exchanges()).extracting(Exchange::method).containsExactly("POST");
        assertThat(UPSTREAM.exchanges().getFirst().requestOnConnection()).isGreaterThan(1);
    }

    //the service answered its status and headers and was gone before the body: nothing has been
    //sent to the caller yet, so this one is still told as a 502 with a body. It is not repeated -
    //the body is read outside the Retry filter
    @Test
    void should_answer_bad_gateway_when_the_connection_is_reset_between_headers_and_body() {
        UPSTREAM.failNext(1, Failure.RESET_AFTER_HEADERS);

        expectUpstreamUnavailable(client.get().uri(PATH).exchange());

        assertThat(UPSTREAM.exchanges()).extracting(Exchange::method).containsExactly("GET");
    }

    private static void expectUpstreamUnavailable(final WebTestClient.ResponseSpec response) {
        response
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors.length()").isEqualTo(1)
            .jsonPath("$.errors[0].message").isEqualTo("Upstream service unavailable")
            .jsonPath("$.errors[0].details").doesNotExist();
    }
}
