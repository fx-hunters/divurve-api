package com.divurve.domain.notification.entity;

import com.divurve.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 사용자 알림 (ERD {@code notifications}, 이슈 #97 — 최소 도메인, <b>조회만</b>).
 * 발송(적재)·읽음 처리 유스케이스는 이 이슈 밖이다 — 이 엔티티는 이미 쌓인 알림을 표현할 뿐이다.
 *
 * <p><b>{@code kind} 값 집합 — ERD {@code notification_type} ENUM 6종을 그대로 쓴다.</b>
 * ({@code step_due}·{@code regime_shift}·{@code deadline_near}·{@code target_zone}·{@code safe_mode}·
 * {@code concentration}) {@code user_settings} 의 알림 스위치는 5종뿐이라({@code notify_safe_mode} 가
 * 없다) 이 집합과 1:1로 대응하지 않는다 — 그 5종은 "사용자가 끌 수 있는 알림 종류"이고, 여기 {@code kind}는
 * "무엇을 알렸는지"이므로 서로 다른 질문이다. {@code safe_mode} 진입 알림은 사용자가 끌 수 없는 시스템
 * 알림으로 읽어, 설정 스위치 집합이 아니라 ERD 가 정의한 6종 전체를 채택했다(V24 마이그레이션 참고).
 *
 * <p><b>ERD 와의 차이</b> — 소유자 FK 는 이 레포 관례인 {@code owner_id}, 읽음 여부는 {@code read_at}
 * 타임스탬프가 아니라 {@code is_read} 불리언이다. 자세한 이유는 V24 마이그레이션 주석 참고.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    /** {@code kind} CHECK 제약 허용값 (V24 마이그레이션과 정확히 일치해야 한다). */
    public static final Set<String> KINDS = Set.of(
            "step_due", "regime_shift", "deadline_near", "target_zone", "safe_mode", "concentration");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected Notification() {
    }

    private Notification(User owner, String kind, String title, String body) {
        this.owner = owner;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.read = false;
    }

    /**
     * 읽지 않은 알림을 만든다. id·created_at 은 저장 시점에 DB 가 채운다.
     *
     * @param owner 알림을 받을 사용자
     * @param kind  알림 종류 — {@link #KINDS} 중 하나여야 한다
     * @param title 제목
     * @param body  본문
     * @return 저장 전 엔티티
     * @throws IllegalArgumentException kind 가 허용값 밖인 경우
     */
    public static Notification create(User owner, String kind, String title, String body) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("허용되지 않은 알림 종류입니다: " + kind);
        }
        return new Notification(owner, kind, title, body);
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
