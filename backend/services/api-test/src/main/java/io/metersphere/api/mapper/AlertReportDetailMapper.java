package io.metersphere.api.mapper;

import io.metersphere.api.domain.ApiReportDetail;
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

    @Select("SELECT id FROM test_plan_report WHERE parent_id=#{reportId} ORDER BY start_time LIMIT 20")
    List<String> childPlanReports(String reportId);
}
