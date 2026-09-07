package com.divurve.domain.user;

/**
 * 사용자 권한 (이슈 #111).
 *
 * <p>값이 둘뿐인 이유 — 이 서비스에 필요한 구분은 "운영자인가 아닌가" 하나다. 세분화된 권한(읽기 전용
 * 운영자, 데이터 편집자 등)은 관리자 화면이 임시인 동안에는 관리 비용만 늘린다. 필요해지면 그때 늘린다.
 *
 * <p>DB 에는 {@code users.role varchar(16)} 으로 저장되고 이름 그대로 매핑된다
 * ({@code @Enumerated(EnumType.STRING)}). 순서에 의존하는 ORDINAL 매핑은 값 순서를 바꾸는 순간
 * 기존 행의 의미가 뒤바뀌므로 쓰지 않는다.
 */
public enum UserRole {

    /** 일반 사용자. 회원가입·데모 계정의 기본값. */
    USER,

    /** 운영자. {@code /api/v1/admin/**} 에 접근할 수 있다. */
    ADMIN
}
