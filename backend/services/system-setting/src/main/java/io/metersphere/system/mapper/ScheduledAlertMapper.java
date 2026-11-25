package io.metersphere.system.mapper;

import io.metersphere.system.dto.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ScheduledAlertMapper {
    @Select("SELECT * FROM scheduled_alert_policy WHERE project_id=#{projectId} AND task_type=#{taskType}")
    ScheduledAlertPolicy getPolicy(@Param("projectId") String projectId, @Param("taskType") String taskType);

    @Select("SELECT * FROM scheduled_alert_policy WHERE project_id=#{projectId} AND task_type=#{taskType} FOR UPDATE")
    ScheduledAlertPolicy lockPolicy(@Param("projectId") String projectId, @Param("taskType") String taskType);

    @Insert("""
        INSERT INTO scheduled_alert_policy(project_id, task_type, failure_threshold, repeat_notifications, failure_template, revision, updated_time)
        VALUES(#{projectId}, #{taskType}, #{failureThreshold}, #{repeatNotifications}, #{failureTemplate}, 1, #{updatedTime})
        ON DUPLICATE KEY UPDATE failure_threshold=IF(revision=#{revision},VALUES(failure_threshold),failure_threshold),
        repeat_notifications=IF(revision=#{revision},VALUES(repeat_notifications),repeat_notifications),
        failure_template=IF(revision=#{revision},VALUES(failure_template),failure_template),
        updated_time=IF(revision=#{revision},VALUES(updated_time),updated_time),
        revision=IF(revision=#{revision},revision+1,revision)
        """)
    int savePolicy(ScheduledAlertPolicy policy);

    @Insert("INSERT INTO scheduled_alert_state(scope_key, project_id, task_type) VALUES(#{scope}, #{projectId}, #{taskType}) ON DUPLICATE KEY UPDATE scope_key=VALUES(scope_key)")
    void createState(@Param("scope") String scope, @Param("projectId") String projectId, @Param("taskType") String taskType);

    @Select("SELECT * FROM scheduled_alert_state WHERE scope_key=#{scope} FOR UPDATE")
    ScheduledAlertState lockState(String scope);

    @Insert("""
        INSERT INTO scheduled_alert_result(scope_key, execution_id, report_id, end_time, failed)
        VALUES(#{scope}, #{result.executionId}, #{result.reportId}, #{result.endTime}, #{result.failed})
        ON DUPLICATE KEY UPDATE failed=IF(VALUES(end_time)>end_time OR (VALUES(end_time)=end_time AND VALUES(report_id)>report_id),VALUES(failed),failed),
        report_id=IF(VALUES(end_time)>end_time OR (VALUES(end_time)=end_time AND VALUES(report_id)>report_id),VALUES(report_id),report_id), end_time=GREATEST(end_time,VALUES(end_time))
        """)
    int addResult(@Param("scope") String scope, @Param("result") ScheduledAlertResult result);

    @Insert("""
        <script>
        INSERT INTO scheduled_alert_result(scope_key, execution_id, report_id, end_time, failed) VALUES
        <foreach collection="results" item="result" separator=",">
          (#{scope}, #{result.executionId}, #{result.reportId}, #{result.endTime}, #{result.failed})
        </foreach>
        ON DUPLICATE KEY UPDATE failed=IF(VALUES(end_time)&gt;end_time OR (VALUES(end_time)=end_time AND VALUES(report_id)&gt;report_id),VALUES(failed),failed),
        report_id=IF(VALUES(end_time)&gt;end_time OR (VALUES(end_time)=end_time AND VALUES(report_id)&gt;report_id),VALUES(report_id),report_id), end_time=GREATEST(end_time,VALUES(end_time))
        </script>
        """)
    void addResults(@Param("scope") String scope, @Param("results") List<ScheduledAlertResult> results);

    @Select("SELECT execution_id, report_id, end_time, failed, notified FROM scheduled_alert_result WHERE scope_key=#{scope} ORDER BY end_time DESC, report_id DESC LIMIT 100")
    List<ScheduledAlertResult> latestResults(String scope);

    @Delete("""
        DELETE FROM scheduled_alert_result WHERE scope_key=#{scope} AND execution_id NOT IN
        (SELECT execution_id FROM (SELECT execution_id FROM scheduled_alert_result WHERE scope_key=#{scope}
         ORDER BY end_time DESC, report_id DESC LIMIT 100) retained)
        """)
    void pruneResults(String scope);

    @Update("UPDATE scheduled_alert_result SET notified=1 WHERE scope_key=#{scope} AND execution_id=#{executionId}")
    void markNotified(@Param("scope") String scope, @Param("executionId") String executionId);

    @Update("UPDATE scheduled_alert_state SET last_notified_execution=#{executionId}, last_recovery_time=#{recoveryTime} WHERE scope_key=#{scope}")
    void notified(@Param("scope") String scope, @Param("executionId") String executionId, @Param("recoveryTime") long recoveryTime);
}
