// JPA 엔티티와 QueryDSL 을 쓰는 모듈의 컨벤션이다.
plugins {
    alias(conventions.plugins.loopers.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.kapt)
}

// plugin.spring 은 Spring 애노테이션이 붙은 클래스만 연다. 엔티티도 열어야 Hibernate 가 LAZY 연관의 프록시를 만든다.
// BaseEntity 의 getter 가 final 이면 하위 엔티티의 프록시 팩토리를 만들지 못한다(HHH000305).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // QueryDSL 의 Q 클래스를 만든다. TOML 은 classifier 를 적지 못해 여기서 jakarta 를 붙인다.
    kapt(variantOf(libs.querydsl.apt) { classifier("jakarta") })
}
