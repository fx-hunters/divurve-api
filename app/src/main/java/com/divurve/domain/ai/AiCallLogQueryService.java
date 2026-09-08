package com.divurve.domain.ai;

import com.divurve.common.architecture.UseCase;
import com.divurve.common.exception.InvalidRequestException;
import com.divurve.domain.ai.entity.AiCallLog;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자용 AI 호출 로그 조회 (이슈 #143).
 *
 * <p>목록과 집계 두 가지를 낸다. <b>관리자 화면에서 실제로 보는 것은 집계</b>다 — 행 단위 목록만
 * 으로는 "이번 주 비용이 왜 늘었는가" 가 보이지 않는다. 목록은 집계에서 이상한 날을 찾은 뒤
 * 그 안을 들여다보는 용도다.
 *
 * <p><b>비용 금액은 내지 않는다.</b> 토큰 수까지만 낸다 — 모델별 단가는 개정되고, 어떤 단가를
 * 적용할지는 팀이 정할 문제다. 금액을 여기서 만들면 그 결정이 코드에 묻힌다.
 */
@UseCase
public class AiCallLogQueryService {

    /** 한 페이지 최대 크기. {@code AdminUserQueryService} 와 같은 값을 쓴다. */
    static final int MAX_PAGE_SIZE = 200;

    static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * 열린 구간을 대신하는 경계값.
     *
     * <p>{@code null} 을 리포지토리로 넘기지 않는 이유는 {@code AiCallLogRepository#searchForAdmin}
     * javadoc 에 있다 — Hibernate 가 null {@code Instant} 를 PostgreSQL 이 {@code timestamp} 로
     * 캐스트할 수 없는 타입으로 바인딩해, 기간 필터 없는 조회가 그대로 실패한다. 경계를 여기서
     * 채우면 그 문제가 생길 자리 자체가 없어진다.
     */
    static final Instant OPEN_START = Instant.EPOCH;

    /** 위와 같은 이유의 상한. 이 서비스의 수명보다 충분히 뒤다. */
    static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

    private final AiCallLogRepository aiCallLogRepository;

    public AiCallLogQueryService(AiCallLogRepository aiCallLogRepository) {
        this.aiCallLogRepository = Objects.requireNonNull(aiCallLogRepository, "aiCallLogRepository");
    }

    /**
     * 호출 로그를 페이지 단위로 조회한다. 최신순이다.
     *
     * @param filter 조회 조건. 각 항목이 {@code null} 이면 그 조건은 없다
     * @param page   0부터 시작하는 페이지 번호
     * @param size   페이지 크기. {@code null} 이면 기본값
     * @throws InvalidRequestException 페이지 번호·크기가 범위를 벗어나거나 어휘가 잘못된 경우 (400)
     */
    @Transactional(readOnly = true)
    public CallLogPage list(CallLogFilter filter, int page, Integer size) {
        Objects.requireNonNull(filter, "filter");
        if (page < 0) {
            throw new InvalidRequestException("페이지 번호는 0 이상이어야 합니다.", "page");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(
                    "페이지 크기는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.", "size");
        }
        requireRange(filter.from(), filter.to());

        Page<AiCallLog> found = aiCallLogRepository.searchForAdmin(
                filter.from() == null ? OPEN_START : filter.from(),
                filter.to() == null ? OPEN_END : filter.to(),
                requireVocabulary(filter.purpose(), purposeCodes(), "purpose"),
                blankToNull(filter.surface()),
                requireVocabulary(filter.outcome(), outcomeCodes(), "outcome"),
                filter.demo(),
                PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "requestedAt")));

        return new CallLogPage(
                found.getContent().stream().map(AiCallLogQueryService::toView).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * 일자·용도·모델별 사용량을 집계한다.
     *
     * @param from 이 시각 이후. {@code null} 이면 제한 없음
     * @param to   이 시각 이전. {@code null} 이면 제한 없음
     * @throws InvalidRequestException 기간이 뒤집힌 경우 (400)
     */
    @Transactional(readOnly = true)
    public List<UsageBucket> summarize(Instant from, Instant to) {
        requireRange(from, to);
        return aiCallLogRepository.summarize(from, to).stream()
                .map(AiCallLogQueryService::toBucket)
                .toList();
    }

    /** 기간이 뒤집혀 있으면 빈 결과가 아니라 400 을 낸다 — 조용한 빈 화면은 원인을 숨긴다. */
    private void requireRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from 은 to 보다 이후일 수 없습니다.", "from");
        }
    }

    /**
     * 닫힌 어휘를 검증한다. 오타를 빈 결과로 돌려주면 "그 기간에 호출이 없었다" 로 읽혀,
     * 비용을 확인하려던 사람이 정확히 반대 결론을 얻는다.
     */
    private String requireVocabulary(String value, List<String> allowed, String field) {
        String normalized = blankToNull(value);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new InvalidRequestException(
                    field + " 는 " + String.join("/", allowed) + " 중 하나여야 합니다.", field);
        }
        return normalized;
    }

    private static List<String> purposeCodes() {
        return java.util.Arrays.stream(AiCallPurpose.values()).map(AiCallPurpose::code).toList();
    }

    private static List<String> outcomeCodes() {
        return java.util.Arrays.stream(AiCallOutcome.values()).map(AiCallOutcome::code).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static CallLogView toView(AiCallLog log) {
        return new CallLogView(
                log.getId(),
                log.getRequestedAt(),
                log.getUserId(),
                log.isDemo(),
                log.getPurpose(),
                log.getSurface(),
                log.getModel(),
                log.getInputTokens(),
                log.getOutputTokens(),
                log.getCacheReadInputTokens(),
                log.getCacheCreationInputTokens(),
                log.getOutcome(),
                log.getFallbackReason(),
                log.getLatencyMs(),
                log.getErrorSummary());
    }

    /** 네이티브 집계 결과 한 줄을 옮긴다. 컬럼 순서는 {@code AiCallLogRepository#summarize} 와 같다. */
    private static UsageBucket toBucket(Object[] row) {
        return new UsageBucket(
                ((Date) row[0]).toLocalDate(),
                (String) row[1],
                (String) row[2],
                ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue());
    }

    /**
     * 조회 조건.
     *
     * @param from    이 시각 이후
     * @param to      이 시각 이전
     * @param purpose {@code narrate}/{@code extract}
     * @param surface 서술 대상 화면
     * @param outcome 호출 결과
     * @param demo    데모 트래픽만/일반만
     */
    public record CallLogFilter(
            Instant from,
            Instant to,
            String purpose,
            String surface,
            String outcome,
            Boolean demo) {
    }

    /** 페이지 결과. */
    public record CallLogPage(
            List<CallLogView> items, int page, int size, long totalElements, int totalPages) {
    }

    /** 호출 한 건. */
    public record CallLogView(
            UUID id,
            Instant requestedAt,
            UUID userId,
            boolean demo,
            String purpose,
            String surface,
            String model,
            long inputTokens,
            long outputTokens,
            Long cacheReadInputTokens,
            Long cacheCreationInputTokens,
            String outcome,
            String fallbackReason,
            Integer latencyMs,
            String errorSummary) {
    }

    /**
     * 하루·용도·모델별 집계 한 칸.
     *
     * @param day 호출 일자 (UTC 기준). 표시 시간대는 화면의 선택이다
     */
    public record UsageBucket(
            LocalDate day,
            String purpose,
            String model,
            long calls,
            long inputTokens,
            long outputTokens) {
    }
}
