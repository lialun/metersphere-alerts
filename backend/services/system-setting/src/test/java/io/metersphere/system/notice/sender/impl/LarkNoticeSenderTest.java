package io.metersphere.system.notice.sender.impl;

import io.metersphere.system.domain.User;
import io.metersphere.system.mapper.ExtSystemProjectMapper;
import io.metersphere.system.notice.MessageDetail;
import io.metersphere.system.notice.NoticeModel;
import io.metersphere.system.notice.Receiver;
import io.metersphere.system.notice.utils.LarkClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LarkNoticeSenderTest {
    @Test void recipientsDoNotBecomePartOfTheReportLink() {
        User user = new User();
        user.setEmail("admin@example.com");
        user.setName("Administrator");
        LarkNoticeSender sender = new LarkNoticeSender();
        ExtSystemProjectMapper users = mock(ExtSystemProjectMapper.class);
        when(users.getEnableProjectMemberByUserId("project", List.of("admin"))).thenReturn(List.of(user));
        ReflectionTestUtils.setField(sender, "extSystemProjectMapper", users);
        MessageDetail message = new MessageDetail();
        message.setProjectId("project");
        message.setWebhook("https://bot.example/webhook");
        NoticeModel notice = NoticeModel.builder().excludeSelf(false)
                .receivers(List.of(new Receiver("admin", "SYSTEM_NOTICE"))).build();
        String reportLink = "https://metersphere.example/#/share/shareReportScenario?shareId=this-report";
        try (var transport = mockStatic(LarkClient.class)) {
            sender.sendLark(message, notice, reportLink, "Alert");
            transport.verify(() -> LarkClient.send("https://bot.example/webhook",
                    "Alert: \n" + reportLink + "\n<at email=\"admin@example.com\">Administrator</at>"));
        }
    }
}
