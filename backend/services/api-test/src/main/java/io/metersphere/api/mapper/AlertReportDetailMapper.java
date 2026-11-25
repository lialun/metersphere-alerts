package io.metersphere.api.mapper;

import io.metersphere.api.domain.ApiReportDetail;
import io.metersphere.system.dto.ScheduledAlertResult;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AlertReportDetailMapper {
    @Select("""
        SELECT id, report_id, step_id, request_name, code,
          CASE WHEN OCTET_LENGTH(content) <= 1048576 THEN content ELSE NULL END AS content
        FROM api_report_detail WHERE report_id=#{reportId} AND status='ERROR' ORDER BY step_id, id LIMIT 21
        """)
    List<ApiReportDetail> failures(String reportId);

    @Select("""
        SELECT d.id, d.report_id, d.step_id, d.request_name, d.code,
          CASE WHEN OCTET_LENGTH(b.content) <= 1048576 THEN b.content ELSE NULL END AS content
        FROM api_scenario_report_detail d LEFT JOIN api_scenario_report_detail_blob b ON d.id=b.id
        WHERE d.report_id=#{reportId} AND d.status='ERROR' ORDER BY d.step_id, d.id LIMIT 21
        """)
    List<ApiReportDetail> scenarioFailures(String reportId);

    @Select("""
        SELECT api_case_execute_report_id FROM test_plan_report_api_case
        WHERE test_plan_report_id=#{reportId} AND api_case_execute_result='ERROR'
        ORDER BY pos LIMIT 20
        """)
    List<String> planCaseReports(String reportId);

    @Select("""
        SELECT api_scenario_execute_report_id FROM test_plan_report_api_scenario
        WHERE test_plan_report_id=#{reportId} AND api_scenario_execute_result='ERROR'
        ORDER BY pos LIMIT 20
        """)
    List<String> planScenarioReports(String reportId);

    @Select("""
        SELECT r.id AS report_id, r.end_time, (r.status='ERROR') AS failed
        FROM api_report r JOIN api_test_case_record c ON c.api_report_id=r.id
        WHERE r.project_id=#{project} AND c.api_test_case_id=#{resource} AND r.environment_id <=> #{environment}
          AND r.trigger_mode='SCHEDULE' AND r.exec_status='COMPLETED'
        ORDER BY r.end_time DESC, r.id DESC LIMIT 100
        """)
    List<ScheduledAlertResult> completedCases(@Param("project") String project, @Param("resource") String resource,
                                            @Param("environment") String environment);

    @Select("""
        SELECT r.id AS report_id, r.end_time, (r.status='ERROR') AS failed
        FROM api_scenario_report r JOIN api_scenario_record c ON c.api_scenario_report_id=r.id
        WHERE r.project_id=#{project} AND c.api_scenario_id=#{resource} AND r.environment_id <=> #{environment}
          AND r.trigger_mode='SCHEDULE' AND r.exec_status='COMPLETED'
        ORDER BY r.end_time DESC, r.id DESC LIMIT 100
        """)
    List<ScheduledAlertResult> completedScenarios(@Param("project") String project, @Param("resource") String resource,
                                                @Param("environment") String environment);

    @Select("""
        SELECT id AS report_id, end_time, (result_status!='SUCCESS') AS failed
        FROM test_plan_report WHERE project_id=#{project} AND test_plan_id=#{resource}
          AND trigger_mode='SCHEDULE' AND exec_status='COMPLETED'
        ORDER BY end_time DESC, id DESC LIMIT 100
        """)
    List<ScheduledAlertResult> completedPlans(@Param("project") String project, @Param("resource") String resource);

    @Select("SELECT id FROM test_plan_report WHERE parent_id=#{reportId} ORDER BY start_time LIMIT 20")
    List<String> childPlanReports(String reportId);
}
