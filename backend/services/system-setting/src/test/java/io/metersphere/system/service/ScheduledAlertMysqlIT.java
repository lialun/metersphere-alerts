package io.metersphere.system.service;

import io.metersphere.system.dto.*;
import io.metersphere.system.mapper.ScheduledAlertMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit Docker/MySQL integration test; run with -Dtest=ScheduledAlertMysqlIT. */
class ScheduledAlertMysqlIT {
    static MySQLContainer<?> mysql;
    static DataSource dataSource;
    static AnnotationConfigApplicationContext context;
    ScheduledAlertService service;
    JdbcTemplate jdbc;
    long clock;

    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean PlatformTransactionManager transactionManager() { return new DataSourceTransactionManager(dataSource); }
        @Bean SqlSessionFactory sqlSessionFactory() throws Exception {
            SqlSessionFactoryBean bean = new SqlSessionFactoryBean(); bean.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true); config.addMapper(ScheduledAlertMapper.class);
            bean.setConfiguration(config); return bean.getObject();
        }
        @Bean ScheduledAlertMapper mapper(SqlSessionFactory factory) { return new SqlSessionTemplate(factory).getMapper(ScheduledAlertMapper.class); }
        @Bean ScheduledAlertService alerts(ScheduledAlertMapper mapper) { return new ScheduledAlertService(mapper); }
    }
    @BeforeAll static void start() {
        mysql = new MySQLContainer<>("mysql:8.0.44"); mysql.start();
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE project (id VARCHAR(50) PRIMARY KEY) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        new ResourceDatabasePopulator(new ClassPathResource("migration/3.6.10/ddl/V3.6.10_3__scheduled_alert.sql")).execute(dataSource);
        context = new AnnotationConfigApplicationContext(Config.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); if (mysql != null) mysql.stop(); }
    @BeforeEach void reset() {
        service = context.getBean(ScheduledAlertService.class); jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM project"); jdbc.update("INSERT INTO project(id) VALUES('project'),('other')");
        clock = System.currentTimeMillis() + 10000;
    }
    ScheduledAlertDecision result(String id, boolean failed) { return result("project", "resource", "env", id, failed); }
    ScheduledAlertDecision result(String project, String resource, String env, String id, boolean failed) {
        ScheduledAlertResult result = new ScheduledAlertResult(); result.setReportId(id);
        result.setEndTime(clock + Integer.parseInt(id)); result.setFailed(failed);
        return service.record(project, "API_SCENARIO_TASK", resource, env, result);
    }
    void policy(int threshold, boolean repeat) {
        ScheduledAlertPolicy policy = service.getPolicy("project", "API_SCENARIO_TASK");
        policy.setFailureThreshold(threshold); policy.setRepeatNotifications(repeat);
        service.savePolicy(policy);
    }
    @Test void migrationUpsertThresholdRecoveryAndProcessRestart() {
        policy(2, true);
        assertNull(result("1", true)); assertNull(result("1", true));
        try (AnnotationConfigApplicationContext restarted = new AnnotationConfigApplicationContext(Config.class)) {
            ScheduledAlertResult result = new ScheduledAlertResult(); result.setReportId("2"); result.setEndTime(clock+2); result.setFailed(true);
            assertEquals(2, restarted.getBean(ScheduledAlertService.class)
                    .record("project", "API_SCENARIO_TASK", "resource", "env", result).consecutiveFailures());
        }
        assertNull(result("2", true)); assertNull(result("3", false)); assertNull(result("4", true));
        assertNotNull(result("5", true));
    }
    @Test void projectsResourcesEnvironmentsAndRevisionsAreIsolated() {
        policy(2, true);
        assertNull(result("1", true));
        assertNull(result("project", "different", "env", "2", true));
        assertNull(result("project", "resource", "other", "3", true));
        assertNotNull(result("other", "resource", "env", "4", true)); // default threshold 1
        policy(2, true); assertNull(result("5", true));
    }
    @Test void optimisticEditsAndTemplateValidation() {
        ScheduledAlertPolicy stale = service.getPolicy("project", "API_SCENARIO_TASK");
        policy(2, true); assertThrows(RuntimeException.class, () -> service.savePolicy(stale));
        ScheduledAlertPolicy policy = service.getPolicy("project", "API_SCENARIO_TASK");
        policy.setFailureTemplate("${resourceName}: ${failureDetails}");
        assertEquals(2, service.savePolicy(policy).getRevision());
        policy.setFailureTemplate("${unknown}"); assertThrows(RuntimeException.class, () -> service.savePolicy(policy));
    }
    @Test void concurrentDuplicateCallbacksProduceOneNotificationDecision() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<ScheduledAlertDecision>> calls = new ArrayList<>();
            for (int i=0; i<20; i++) { calls.add(() -> result("1", true)); }
            long allowed=0; for (Future<ScheduledAlertDecision> future : executor.invokeAll(calls)) { if(future.get()!=null) allowed++; }
            assertEquals(1, allowed);
        }
    }
    @Test void concurrentFailuresOnceUntilRecoveryAndBoundedHistory() throws Exception {
        policy(2, false);
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<ScheduledAlertDecision>> calls = new ArrayList<>();
            for (int i=1; i<=20; i++) { String id=String.valueOf(i); calls.add(() -> result(id, true)); }
            long allowed=0; for (Future<ScheduledAlertDecision> future : executor.invokeAll(calls)) { if(future.get()!=null) allowed++; }
            assertEquals(1, allowed);
        }
        for(int i=21;i<=120;i++) assertNull(result(String.valueOf(i), true));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_alert_result", Integer.class));
        assertNull(result("121", false)); assertNull(result("122", true)); assertNotNull(result("123", true));
        jdbc.update("DELETE FROM project");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_alert_state", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_alert_result", Integer.class));
    }
}
