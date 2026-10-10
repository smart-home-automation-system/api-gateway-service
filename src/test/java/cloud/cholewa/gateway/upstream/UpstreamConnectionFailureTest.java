package cloud.cholewa.gateway.upstream;

import cloud.cholewa.gateway.upstream.StubUpstream.Exchange;
import cloud.cholewa.gateway.upstream.StubUpstream.Failure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

//HAS-212: the gateway once answered a GET with 500 and no body, because the connection it took
//from its pool had been reset on the way to the service. Here the gateway runs on a real port and
//every route points at a stub that breaks a connection the gateway keeps alive
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
class UpstreamConnectionFailureTest {

    private static final StubUpstream UPSTREAM = new StubUpstream();
    private static final String PATH = "/home/water/status/temperature";
    private static final String WRITE = "{\"turn\":\"on\",\"note\":\"a body the gateway has to pass on\"}";

    //whether the gateway held the body of a request in memory when it routed it, per request
    private static final List<Boolean> BODY_CACHED = new CopyOnWriteArrayList<>();

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void upstream(final DynamicPropertyRegistry registry) {
        for (String service : List.of("ai", "amx", "boiler", "database", "heating", "presence", "water")) {
            registry.add("internal.service." + service + ".host", () -> "localhost");
            registry.add("internal.service." + service + ".port", UPSTREAM::port);
        }
    }

    @TestConfiguration
    static class RoutingProbe {

        //runs right before the request is sent to the target
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE - 10)
        GlobalFilter bodyCachingProbe() {
            return (exchange, chain) -> {
                BODY_CACHED.add(
                    exchange.getAttributes().containsKey(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR)
                        || exchange.getAttributes()
                        .containsKey(ServerWebExchangeUtils.CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR)
                );
                return chain.filter(exchange);
            };
        }
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
        BODY_CACHED.clear();
    }

    //a path of every route in RoutesConfig: the repetition belongs to the gateway, not to a route,
    //so a route added later has it without anyone deciding it - add its path here all the same
    @ParameterizedTest
    @ValueSource(strings = {
        "/home/ai",
        "/home/amx",
        "/home/boiler/status",
        "/home/household/profiles",
        "/home/heating/rooms",
        "/home/presence/house/report",
        PATH
    })
    void should_repeat_a_read_whose_pooled_connection_was_reset(final String path) {
        UPSTREAM.failNext(1, Failure.RESET_BEFORE_RESPONSE);

        client.get().uri(path)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(HttpHeaders.ETAG, StubUpstream.ETAG)
            .expectBody(String.class).isEqualTo(StubUpstream.BODY);

        List<Exchange> exchanges = UPSTREAM.exchanges();

        assertThat(exchanges).extracting(Exchange::method).containsExactly("GET", "GET");
        assertThat(exchanges).extracting(Exchange::path).containsOnly(path);
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
            client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(WRITE).exchange()
        );

        assertThat(UPSTREAM.exchanges()).extracting(Exchange::method).containsExactly("POST");
        assertThat(UPSTREAM.exchanges().getFirst().requestOnConnection()).isGreaterThan(1);
    }

    //the Retry filter of Spring Cloud Gateway made the gateway keep the body of every request in
    //memory, to be able to send it again. Nothing is sent again that has a body, so nothing is kept
    @Test
    void should_pass_the_body_of_a_write_on_without_keeping_it() {
        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(WRITE)
            .exchange()
            .expectStatus().isOk();
        client.get().uri(PATH).exchange().expectStatus().isOk();

        assertThat(UPSTREAM.exchanges())
            .extracting(Exchange::method, Exchange::bodyLength)
            .containsExactly(tuple("POST", WRITE.length()), tuple("GET", 0));
        assertThat(BODY_CACHED).containsExactly(false, false);
    }

    //the service answered its status and headers and was gone before the body: nothing has been
    //sent to the caller yet, so this one is still told as a 502 with a body - and with nothing of
    //the answer that never came. It is not repeated: an answer had begun
    @Test
    void should_answer_bad_gateway_without_the_headers_of_an_answer_that_broke_off() {
        UPSTREAM.failNext(1, Failure.CLOSE_AFTER_HEADERS);

        expectUpstreamUnavailable(client.get().uri(PATH).exchange())
            .expectHeader().doesNotExist(HttpHeaders.ETAG)
            .expectHeader().doesNotExist(HttpHeaders.CACHE_CONTROL)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);

        assertThat(UPSTREAM.exchanges()).extracting(Exchange::method).containsExactly("GET");
    }

    private static WebTestClient.ResponseSpec expectUpstreamUnavailable(final WebTestClient.ResponseSpec response) {
        response
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.errors.length()").isEqualTo(1)
            .jsonPath("$.errors[0].message").isEqualTo("Upstream service unavailable")
            .jsonPath("$.errors[0].details").doesNotExist();

        return response;
    }
}
