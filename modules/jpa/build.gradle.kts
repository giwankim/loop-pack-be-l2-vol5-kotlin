plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
    `java-test-fixtures`
}

// BaseEntity 의 getter 가 final 이면 Hibernate 가 하위 엔티티의 프록시 팩토리를 만들지 못한다(HHH000305). commerce-api 와 같은 규칙.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    // jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    // querydsl
    api("com.querydsl:querydsl-jpa::jakarta")
    kapt("com.querydsl:querydsl-apt::jakarta")
    // jdbc-mysql
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.testcontainers:mysql")

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testFixturesImplementation("org.testcontainers:mysql")
}
