package com.divurve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divurve.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.divurve.engine.bucket.BucketAllocator;
import com.divurve.engine.cost.CostCalculator;
import com.divurve.engine.volatility.RegimeClassifier;
import com.divurve.engine.simulate.MonteCarloSimulator;
import com.divurve.engine.split.SplitVarianceReducer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 전체 Spring 컨텍스트 기동 스모크 테스트 (이슈 #38).
 *
 * <p>이 테스트가 잡는 것 — 단위 테스트로는 원리적으로 잡을 수 없는 기동 시점 결함들이다.
 * <ul>
 *   <li>engine 계산기의 {@code EngineConfig} 빈 등록 누락 → {@code NoSuchBeanDefinitionException}.
 *       {@code @EngineComponent} 는 스테레오타입이 아니라 컴포넌트 스캔 대상이 아니므로
 *       수동 등록이 필요한데, 누락돼도 컴파일·단위 테스트는 전부 통과한다.</li>
 *   <li>Flyway 마이그레이션 버전 중복 → {@code FlywayException}.
 *       병렬 브랜치가 같은 버전 번호를 쓰면 발생한다.</li>
 *   <li>컨트롤러 간 경로 중복 → {@code IllegalStateException: Ambiguous mapping}.
 *       스텁 컨트롤러를 지우지 않고 실구현 컨트롤러를 새로 만들면 발생한다.</li>
 *   <li>엔티티-스키마 불일치 → {@code ddl-auto: validate} 가 기동 시 검증한다.</li>
 * </ul>
 *
 * <p>2026-09-06 develop 대규모 CI 실패에서 위 세 가지가 <b>모두 동시에</b> 실재했으나,
 * 컨텍스트를 띄우는 테스트가 {@code @DataJpaTest} 슬라이스뿐이어서 CI 가 하나도 잡지 못했다.
 *
 * <p>컨텍스트 캐시가 갈라지면 CI 시간이 배로 늘어나므로 {@code @SpringBootTest} 는 이 클래스
 * 하나로 유지한다. 프로퍼티 오버라이드나 {@code @MockBean} 을 여기에 추가하지 말 것.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationContextSmokeTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerDatasource(registry);
    }

    @Autowired
    private ApplicationContext context;

    // actuator(이슈 #145)가 controllerEndpointHandlerMapping 을 등록하면서 이 타입의 후보가 둘이 됐다.
    // 검증 대상은 @RestController 매핑이므로 기본 빈을 이름으로 고정한다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 전체_컨텍스트가_기동된다() {
        assertThat(context).isNotNull();
    }

    /**
     * 생성자 주입 대상인 engine 계산기가 빠짐없이 빈으로 등록되었는지 확인한다.
     * 이 5개는 이슈 #38 이전에 실제로 등록이 누락돼 있었다 — 앱이 기동 자체를 못 하는 상태였다.
     */
    @Test
    void 주입_대상_engine_계산기가_모두_빈으로_등록된다() {
        assertThat(context.getBean(BucketAllocator.class)).isNotNull();
        assertThat(context.getBean(SplitVarianceReducer.class)).isNotNull();
        assertThat(context.getBean(CostCalculator.class)).isNotNull();
        assertThat(context.getBean(MonteCarloSimulator.class)).isNotNull();
        assertThat(context.getBean(RegimeClassifier.class)).isNotNull();
    }

    /**
     * 컨트롤러 매핑이 등록되었는지 확인한다.
     * 경로가 중복되면 이 빈을 만드는 시점에 ambiguous mapping 으로 기동이 실패하므로,
     * 주입에 성공했다는 것 자체가 중복 매핑이 없다는 증거다.
     */
    @Test
    void 컨트롤러_매핑이_중복_없이_등록된다() {
        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
    }

    /**
     * OpenAPI 문서가 {@code userId} 를 요청 파라미터로 광고하지 않는지 확인한다 (이슈 #50).
     *
     * <p>{@code @CurrentUser} 는 커스텀 리졸버가 채우는 파라미터라, springdoc 에 알려주지 않으면
     * 모든 보호 엔드포인트에 {@code userId} 쿼리 파라미터가 있는 것처럼 문서가 만들어진다 —
     * 이슈 #50 에서 제거한 취약한 형태를 문서가 프론트에 다시 권하게 된다.
     * {@code OpenApiConfig} 가 그 어노테이션을 문서 생성에서 제외한다.
     */
    @Test
    void OpenAPI_문서가_userId_를_요청_파라미터로_노출하지_않는다() throws Exception {
        String apiDocs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode paths = new ObjectMapper().readTree(apiDocs).path("paths");
        assertThat(paths).isNotEmpty();

        List<String> 노출된_userId_파라미터 = new ArrayList<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            for (JsonNode parameter : operation.getValue().path("parameters")) {
                if ("userId".equals(parameter.path("name").asText())
                        || "user_id".equals(parameter.path("name").asText())) {
                    노출된_userId_파라미터.add(path.getKey() + " " + operation.getKey());
                }
            }
        }));

        assertThat(노출된_userId_파라미터).isEmpty();
    }

    /**
     * 슬립 방지 워크플로가 찌르는 경로가 앱에 실제로 존재하는지 확인한다 (이슈 #145).
     *
     * <p>이 테스트가 존재하는 이유 — 최초 초안이 {@code /swagger-ui/swagger-ui/index.html} 라는
     * 중복 경로를 찌르고 있었는데, springdoc 의 webjar 정적 리소스에 <b>우연히</b> 걸려 200 이 나왔다.
     * 게다가 초안의 {@code curl ... || echo} 는 HTTP 404 에도 exit 0 이라(curl 은 상태 코드로 실패하지
     * 않는다) 경로가 깨져도 워크플로는 영원히 초록불이었을 것이다. 즉 "핑이 도착하지 않는데 아무도
     * 모르는" 상태가 만들어진다 — 이 테스트는 그 조합을 CI 에서 잡는다.
     *
     * <p>워크플로 파일에서 URL 을 직접 읽어 검증하므로, 나중에 누가 경로를 바꾸면 여기서 걸린다.
     */
    @Test
    void 슬립_방지_워크플로의_핑_경로가_실제로_응답한다() throws Exception {
        // 테스트 작업 디렉터리는 app/ 이므로 레포 루트는 한 단계 위다 (MigrationVersionTest 와 동일).
        Path 워크플로 = Path.of("..", ".github", "workflows", "keep-awake.yml");
        assertThat(Files.exists(워크플로))
                .as("keep-awake.yml 이 있어야 한다")
                .isTrue();

        Matcher m = Pattern.compile("https://divurve-api\\.onrender\\.com(/\\S*)")
                .matcher(Files.readString(워크플로));
        assertThat(m.find())
                .as("워크플로에서 핑 대상 URL 을 찾지 못했다")
                .isTrue();

        String 핑_경로 = m.group(1).replaceAll("['\"]+$", "");
        mockMvc.perform(get(핑_경로)).andExpect(status().isOk());
    }

    /**
     * actuator 가 health 외의 엔드포인트를 열지 않는지 확인한다 (이슈 #145).
     *
     * <p>이 레포는 Spring Security 를 쓰지 않고 {@code WebAuthConfig} 의 인터셉터는 {@code /api/**}
     * 에만 붙는다 — actuator 경로에 인증을 걸 수단이 없다. 따라서 노출하는 순간 그대로 공개된다.
     * {@code env} 는 JWT 시크릿·DB 접속 정보가, {@code beans}·{@code mappings} 는 내부 구조가 샌다.
     */
    @Test
    void actuator_는_health_외의_엔드포인트를_노출하지_않는다() throws Exception {
        for (String 열리면_안_되는_경로 : List.of(
                "/actuator/env", "/actuator/beans", "/actuator/metrics",
                "/actuator/mappings", "/actuator/configprops", "/actuator/loggers")) {
            mockMvc.perform(get(열리면_안_되는_경로))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * health 응답이 상세 정보를 담지 않는지 확인한다 (이슈 #145).
     *
     * <p>{@code show-details: never} 가 풀리면 DB 접속 URL·디스크 경로 등이 인증 없이 노출된다.
     */
    @Test
    void health_응답이_상세_정보를_노출하지_않는다() throws Exception {
        String 응답 = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode health = new ObjectMapper().readTree(응답);
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.has("components")).as("components 가 노출되면 안 된다").isFalse();
        assertThat(health.has("details")).as("details 가 노출되면 안 된다").isFalse();
    }
}
