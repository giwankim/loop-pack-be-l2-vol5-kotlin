package com.loopers.support

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DatabaseCleanUp(
    @PersistenceContext private val entityManager: EntityManager,
) : InitializingBean {
    private val tableNames = mutableListOf<String>()

    override fun afterPropertiesSet() {
        entityManager.entityManagerFactory
            .unwrap(SessionFactoryImplementor::class.java)
            .mappingMetamodel
            .forEachEntityDescriptor { entity -> tableNames.add(entity.mappedTableDetails.tableName) }
    }

    @Transactional
    fun truncateAllTables() {
        entityManager.flush()
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate()
        tableNames.forEach { table ->
            entityManager.createNativeQuery("TRUNCATE TABLE `$table`").executeUpdate()
        }
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate()
    }
}
