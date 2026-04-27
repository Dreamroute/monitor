package com.dreamroute.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

@SpringBootTest(properties = "vmrack.stock-monitor.enabled=false")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "send.mail.test", matches = "true")
class SendTest {

    @Resource
    private JavaMailSender javaMailSender;

    @Test
    void mmTest() {
        SimpleMailMessage message = new SimpleMailMessage();
        // 发件人
        message.setFrom("342252328@qq.com");
        // 收件人(可实现批量发送)
        message.setTo("342252328@qq.com");
        // 邮箱标题
        message.setSubject("腾讯新闻");
        // 邮箱内容
        message.setText("腾讯新闻");
        // 发送邮件
        javaMailSender.send(message);
    }
}
