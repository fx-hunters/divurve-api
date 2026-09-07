# Divurve 백엔드 — AI Agent 전역 Role

이 문서는 백엔드 레포 루트의 규칙서다. **이 레포에서 작업하는 모든 AI 에이전트는 다른 지시가 없는 한 이 규칙을 항상 따른다.** 상세 근거는 아래 참고 문서(Notion 원본)를 보고, 여기서는 실무 지침만 담는다.

> 원본: [AI Agent 전역 Role 문서 — Backend (Spring Boot)](https://app.notion.com/p/AI-Agent-Role-Backend-Spring-Boot-3d107f0265f481179ea9e242f279e87d)
> 이 문서와 원본이 충돌하면 원본이 우선한다. 원본 갱신 시 이 문서도 갱신한다.

---

## 0. 참고 문서 (작업 전 반드시 확인)
- **개발 컨벤션** — 용어사전·Git·코드컨벤션·API·DB·테스트 원본 규칙
- **네이밍 규칙 통일 문서 (BE/FE 공통)** — 접미사(`_rate`/`_ratio`/`_krw`/`_code` 등), 금지어
- **Git Issue / PR 템플릿** — 이슈/PR 생성 시 그대로 사용 (이 레포 `.github/` 에 반영됨)

## 1. 제품 원칙 — AI가 계산을 대신하지 않는다
Divurve는 외화 목표·환전 타이밍 제안 서비스이며, **이 레포(백엔드)가 계산 엔진을 전담**한다(몬테카를로 시뮬레이션, 안전/기회 버킷 분리, 변동성·집중도 계산 등). 프론트는 API 응답을 표시만 하고 재계산하지 않는다.

> **LLM은 수치를 생성하지 않는다.** 결정론적 로직(순수 함수/서비스)만 결과를 만든다. 에이전트가 코드를 작성할 때도 이 원칙을 어기지 않는다 — 계산은 반드시 `engine` 모듈의 테스트된 순수 함수로 구현한다.

## 2. 기술 스택
| 항목 | 선택 |
| --- | --- |
| 언어 | Java **17 LTS** (toolchain 고정) |
| 프레임워크 | Spring Boot 3.x |
| 빌드 | Gradle Kotlin DSL, 2모듈(`engine`, `app`) |
| ORM | Spring Data JPA + Hibernate |
| DB | PostgreSQL |
| 마이그레이션 | Flyway (`app/src/main/resources/db/migration`) |
| API 문서 | springdoc-openapi (Swagger UI) |
| JSON 매핑 | Jackson, 전역 SNAKE_CASE 네이밍 전략 |
| 테스트 커버리지 | JaCoCo — 100% 목표, CI 게이트 |
| 아키텍처 검증 | ArchUnit — 어노테이션 레이어 + 패키지 경계 테스트 |

## 3. 모듈 / 패키지 구조
Gradle 모듈은 **`engine`, `app` 2개**. 나머지(common/domain/infra/api)는 `app` 안의 **패키지로만** 구분한다.

```
settings.gradle.kts        include("engine", "app")

engine/                    계산 순수 로직. Spring·JPA 의존 절대 금지. 독립 모듈
  attribution/ volatility/ bucket/ split/ simulate/ cost/ concentration/
  EngineComponent          engine 계산 서비스 마커 어노테이션 (engine 이 app 에 의존하지 않으므로 여기 둔다)

app/                       나머지 전부. 레이어는 패키지로 나눈다
  common/architecture/     WebAdapter / UseCase / PersistenceAdapter / ExternalAdapter 어노테이션
  common/exception/ common/response/   전역 예외 · data+meta 응답 래퍼
  domain/                  goal/ plan/ execution/ holding/ + port/ (FxRateProvider 등 외부 의존 인터페이스)
  infra/                   fxrate/ (EcosFxRateProvider 등, @ExternalAdapter) · scheduler/
  api/                     controller(@WebAdapter) / dto / config(Swagger·전역 예외 핸들러)
  Application.java         Spring Boot 진입점
```

**컴파일 타임 의존 방향은 `app → engine` 이 사실상 전부.** 모듈이 2개뿐이라 순환이 생길 여지가 거의 없다.

### DIP — domain은 infra를 모른다
`domain/port/FxRateProvider`(인터페이스)를 `domain` 이 사용하고, 구현체 `infra/fxrate/EcosFxRateProvider`(`@ExternalAdapter`)는 런타임에 Spring 이 자동 주입한다. 패키지 의존은 `infra → domain`(정상 방향), 런타임 객체 참조는 domain 이 든 필드에 infra 객체가 들어간다 — domain 은 자신을 움직이는 구현이 infra 에 있다는 것을 모른다.

## 4. 아키텍처 검증 (ArchUnit) — CI에서 강제
**새 클래스를 만들 때 해당 레이어 어노테이션을 반드시 붙인다.** 어노테이션이 없으면 어느 레이어에도 속하지 않아 규칙에 안 걸리므로, **누락 자체가 리뷰 반려 대상**이다.

| 어노테이션 | 위치 | 호출 가능 방향 |
| --- | --- | --- |
| `@WebAdapter` | api 컨트롤러 | 최상위 — 아무도 호출 못 함 |
| `@UseCase` | domain 서비스 | Web 에서만 |
| `@PersistenceAdapter` | domain Repository 구현체 | UseCase 에서만 |
| `@ExternalAdapter` | infra 외부/배치 어댑터 | UseCase 에서만 |
| `@EngineComponent` | engine 계산 서비스 | UseCase 에서만 |

- `LayerArchitectureTest` (문서 4.2) — 클래스 레이어(어노테이션) 방향 + `engine`은 Spring/JPA 의존 금지.
- `ModuleArchitectureTest` (문서 4.3) — 패키지 경계: `domain`은 `Infra`/`Api`에서만 접근, `engine`은 `domain`에서만, `api`가 `domain`을 건너뛰고 `infra`를 직접 호출 금지.

두 테스트는 `./gradlew test` 에 포함되어 위반 시 빌드가 실패한다. (뼈대 단계에서는 빈 레이어 허용을 위해 `withOptionalLayers(true)` 를 둠 — 클래스가 생기면 규칙이 그대로 적용된다.)

## 5. 네이밍
- **DB 컬럼 = API 응답 필드**: `snake_case`, 그대로. 줄여 쓰지 않는다.
- Java: 변수·메서드 `camelCase` / 클래스 `PascalCase` / 상수 `UPPER_SNAKE`.
- DTO 는 `camelCase` 로 작성하고 **Jackson 전역 SNAKE_CASE 전략**으로 직렬화만 자동 변환.
- **숫자 경계**: 전역 SNAKE_CASE 전략은 대문자 앞에만 `_` 를 넣고 숫자 앞에는 넣지 않는다
  (`interval80` → `interval80`, `interval_80` 이 아니다). 필드명에 숫자가 붙으면(`[a-z][0-9]`)
  `@JsonProperty` 로 명세의 키(`interval_80`)를 직접 고정한다. 명세에 없는 필드는 숫자 앞에 `_` 를
  넣은 키로 스스로 정한다. `DtoSnakeCaseDigitBoundaryTest` 가 `api/dto` 전체를 직렬화해 이 규약을
  강제한다(이슈 #60).
- 접미사·헷갈리는 용어는 네이밍 규칙 통일 문서 참고.

## 6. API / Swagger
- 버저닝: 모든 경로는 **`/api/v1/...`** (URL 경로 버저닝).
- Swagger UI: `/swagger-ui/index.html`, OpenAPI 스펙: `/v3/api-docs`.
- 응답은 항상 **`data` + `meta`** 로 감싼다.
- 필드명은 DB 컬럼명 그대로. 축약 금지.
- 브레이킹 체인지(필드 삭제·타입 변경)는 **`/api/v2` 신설**, 이슈에 프론트 담당자 태그, 마이그레이션 기간 동안 v1 유지.

## 7. 계산 엔진 원칙
- `engine` 모듈은 **테스트 필수** (split · attribution · simulate · bucket · cost · concentration).
- 함수명에 `predict`, `recommend` 등 **금지어 사용 금지** (네이밍 문서 6장).
- 계산 로직을 바꾸면 커밋 타입은 **`calc`**, 본문에 **변경 전/후 수치**를 반드시 적는다.

## 8. 테스트 커버리지 (JaCoCo, 100%)
- 목표: 라인·브랜치 커버리지 **100%**. 특히 `engine`은 예외 없이 100% — 계산 신뢰성이 제품 주장 전체의 근거다.
- 측정 제외(보일러플레이트): `**/dto/**`, `**/entity/**`, `**/config/**`, `**/port/**`, `**/architecture/**`, `**/*Application.class`. `engine`/`domain`/`infra` **서비스 로직은 예외 없이 포함**한다.
- 검증되는 카운터는 **INSTRUCTION · LINE · BRANCH 세 가지**다. `counter` 를 지정하지 않으면 JaCoCo 기본값인
  INSTRUCTION 만 검사되어 미커버 분기가 그대로 통과한다(이슈 #40에서 수정).
- CI 는 `./gradlew build test jacocoTestCoverageVerification` 을 실행하고 미달 시 빌드를 실패시킨다.
  **push 전 로컬에서 `./gradlew ciCheck` 로 같은 검증을 돌린다** — 선행으로 `export DOCKER_API_VERSION=1.44` 가 필요하다(README 참고).
- 미달 원인은 HTML 리포트로 찾는다. CI 실패 시에도 워크플로 실행 페이지의 Artifacts 에 리포트가 올라간다.

## 9. Git 워크플로 (필수 — 위반 시 반려)
1. 작업 요청을 받으면 **코드보다 먼저 단위 GitHub Issue 를 만든다** (`.github/ISSUE_TEMPLATE` 의 Feature/Bug 템플릿 사용).
2. **현재 브랜치 성격과 요청 작업 유형이 다르면 새 브랜치 생성을 먼저 제안**한다. 브랜치명은 `feat/<scope>` · `fix/<scope>` · `chore/<scope>`. 이슈가 연관되어 있다면, `feat/{이슈번호}-<scope>`. 
3. **커밋은 절대 임의로 하지 않는다.** 커밋 메시지(타입/스코프/한 줄 요약/본문)를 먼저 사용자에게 제시하고, **승인받은 뒤에만** 커밋한다.
4. PR 은 템플릿을 그대로 쓰고, 본문에 **`Closes #이슈번호`** 를 포함한다.
5. **PR 을 올리기 전에 최신 `develop` 을 머지/리베이스한 상태에서 `./gradlew ciCheck` 를 통과시킨다.**
   오래된 베이스에서 받은 green 체크는 근거가 되지 않는다 — 2026-09-06 대규모 CI 실패가 정확히 이 stale-green 머지로 발생했다.
6. `main`/`develop` 으로의 push·PR 은 `.github/workflows/ci.yml` 이 빌드+테스트+커버리지+아키텍처 검증을 돌린다. 없으면 먼저 생성을 제안한다.

## 9.1 병렬 작업 규약 (필수 — 여러 세션·에이전트가 동시에 돈다)

이 레포는 여러 AI 세션이 **동시에** 작업한다. 2026-09-06·09-07 이틀 동안 병렬성 때문에 사고가 세 번 났다.
아래는 각 사고에서 나온 규칙이며, 지키지 않으면 같은 사고가 반복된다.

### 9.1.1 워크트리를 공유하지 않는다
**작업 시작 전에 반드시 확인한다.**

```bash
git worktree list
lsof -a -d cwd -Fn | grep divurve-api   # 어느 워크트리를 누가 점유 중인가
```

메인 워크트리(`/Users/chan/divurve-api`)에 다른 세션이 붙어 있으면 **전용 워크트리를 만들고 거기서 작업한다.**

```bash
git worktree add -b feat/<이슈번호>-<scope> .claude/worktrees/<이슈번호>-<scope> origin/develop
```

- 워크트리 경로는 **하이픈만** 쓴다 (`fix-98-...`). 브랜치명의 `/` 를 경로에 그대로 쓰면 중첩 디렉터리가 생겨 정리가 꼬인다.
- `.claude/worktrees/` 는 `.gitignore` 에 있다. 절대 커밋하지 않는다.
- 작업이 끝나면 `git worktree remove` 로 걷어낸다. **다른 세션이 쓰는 워크트리는 지우지 않는다.**

> **사고 (2026-09-07)**: 한 세션이 메인 워크트리에서 `git merge develop` 을 걸어 충돌 8건으로 멈춘 상태(`MERGE_HEAD` 존재)에서, 다른 세션이 같은 워크트리에서 `git commit` 을 실행했다. git 이 그것을 머지 커밋으로 승격시키며 **충돌 마커가 그대로 커밋**됐고, 삭제했던 파일 3개가 되살아났으며 Flyway 버전이 중복됐다. 뒤 세션은 머지가 진행 중인 줄 몰라 "테스트 1212개 통과" 라고 보고했다 — 머지 이전 결과였다.

### 9.1.2 커밋 직전에 워킹 트리 상태를 확인한다
`git add`/`git commit` **바로 앞**에서:

```bash
git status            # MERGE_HEAD / REBASE_HEAD / 충돌 잔재 확인
git diff --check      # 충돌 마커·공백 오류
grep -rn '^<<<<<<< \|^>>>>>>> ' app/src engine/src   # 마커가 커밋되는 것을 막는 최후 방어선
```

내가 시작하지 않은 머지가 진행 중이면 **커밋하지 말고** 그 워크트리를 쓰는 세션에 먼저 알린다.

### 9.1.3 남의 커밋을 되돌리지 않는다
다른 세션이 만든 커밋이 잘못됐어도 `reset --hard` 로 지우지 않는다 — 그 위에 쌓인 작업이 함께 사라진다.
**복구 커밋을 위에 쌓고**, 무엇을 왜 고쳤는지 커밋 본문에 남긴다. 그리고 해당 세션에 상황을 알린다.

### 9.1.4 Flyway 마이그레이션 번호는 base 의 최대값 + 1 부터 집는다
번호는 **선착순**이다. 브랜치를 딸 때가 아니라 **머지될 때** 무슨 번호가 이미 쓰였는지가 결정된다.

작업 시작 시점과 PR 올리기 직전, 두 번 확인한다:

```bash
git fetch origin
git ls-tree --name-only origin/develop app/src/main/resources/db/migration/ | sort -V | tail -3
./scripts/check-migration-order.sh origin/develop    # CI 와 같은 검사를 로컬에서
```

- **번호를 재사용하지 않는다.** 순서를 바로잡느라 파일을 뒤로 옮기면 번호에 구멍이 생기는데, 그 구멍은 **그대로 둔다**. 이미 쓰인 번호에 다른 내용을 넣으면 그 번호를 옛 내용으로 기록한 DB 와 체크섬이 어긋나 배포가 죽는다.
- 상대 순서가 있는 마이그레이션(expand → contract 등)은 **함께** 옮긴다. 한쪽만 옮기면 순서가 뒤집혀 SQL 이 깨진다.
- CI 의 `migration-order` 잡과 `MigrationVersionTest` 가 이 규칙을 강제한다.

> **사고 (2026-09-06)**: `V7__auth_signup.sql` 과 `V7__user_settings_notifications.sql` 이 서로 다른 브랜치에서 같은 번호를 집어 develop 에 **동시에 존재**했다.
> **사고 (2026-09-07, 이슈 #104)**: `V13` 이 이미 배포·적용된 `V14` 보다 낮은 번호로 나중에 도착해 Flyway 검증이 배포를 거부했다. 로컬 `ciCheck` 는 green 이었다 — Testcontainers 는 매번 빈 DB 라 이 실패가 **구조적으로 재현되지 않는다.**

### 9.1.5 브랜치를 스택으로 쌓지 않는다
`feat/83 → feat/84 → feat/85` 처럼 이어 쌓으면 마지막 하나가 머지될 때까지 **모든 스키마 변경이 develop 도착을 기다린다.** 그 사이 다른 PR 이 번호를 가져가면 9.1.4 사고가 난다.

부득이 쌓아야 하면 **마이그레이션이 있는 브랜치를 먼저 단독으로 머지**한다.

### 9.1.6 다른 세션에 알린다
워크트리·브랜치를 넘겨받거나, 남의 작업을 고쳤거나, 상대가 모르는 사실(진행 중인 머지, 사고성 커밋)이 있으면 해당 세션에 메시지를 보낸다. 세션 목록과 마지막 활동 시각으로 누가 무엇을 하는지 찾을 수 있다.

## 10. 확인 필요 / 미확정 (팀 결정 후 이 문서 갱신)
현재 뼈대는 아래를 **문서 권장 기본값**으로 채워 두었다. 팀 확정 시 갱신한다.
- Java 버전 — **17 가정** (확정 필요)
- Flyway 등 마이그레이션 도구 도입 여부 — **Flyway 포함**해 둠
- Lombok 사용 여부 — **미사용**으로 시작
- 인증 방식(JWT 등) — 해커톤 범위 포함 여부 미정
- 전역 예외 처리·에러 응답 구조 상세 스펙 — `common/exception`·`common/response` 골격만
- JaCoCo 커버리지 제외 패키지 목록 최종 확정
- ArchUnit 레이어 접근 규칙(4장) 팀 리뷰 — 특히 UseCase 가 다른 UseCase 를 호출하는 경우 허용 여부
- infra 실제 외부 연동 대상(ECOS/AlphaVantage) 및 API 키 관리 방식
- domain/infra/api 를 별도 Gradle 모듈로 분리할 시점 — 지금은 패키지 구분만
