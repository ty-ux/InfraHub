package com.infrahub.auth.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.RejectedExecutionException;

@Component
public class AccountMail {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMail.class);
    private final ObjectProvider<MailSender> senders;
    private final TaskExecutor executor;
    private final String from;
    private final String consoleUrl;

    public AccountMail(ObjectProvider<MailSender> senders, @Qualifier("accountMailExecutor") TaskExecutor executor,
                       @Value("${infrahub.auth.mail.from:}") String from,
                       @Value("${infrahub.auth.mail.console-url:}") String consoleUrl) {
        this.senders = senders;
        this.executor = executor;
        this.from = from;
        this.consoleUrl = consoleUrl.isBlank() ? "" : checkedConsoleUrl(consoleUrl);
    }

    public boolean enabled() {
        return !from.isBlank() && senders.getIfAvailable() != null;
    }

    public boolean invitationsEnabled() {
        return enabled() && !consoleUrl.isEmpty();
    }

    public void requireEnabled() {
        if (!enabled()) throw new BizException(ErrorCode.VALIDATION_ERROR, "邮箱服务尚未配置");
    }

    public void sendCode(String email, String code) {
        send(email, "InfraHub 邮箱验证码", "验证码为 " + code + "，10 分钟内有效。请勿转发验证码。");
    }

    public void sendInvitation(String email, String token) {
        send(email, "InfraHub 注册邀请", "请在 24 小时内打开链接并设置密码：\n" + consoleUrl + "/register#token=" + token);
    }

    public void sendLoginCodeLater(String email, String code) {
        enqueue(() -> {
            if (code != null) sendCode(email, code);
        });
    }

    public void notifyPreviousAddress(String email) {
        enqueue(() -> send(email, "InfraHub 邮箱已变更", "你的 InfraHub 登录邮箱已变更。如果并非本人操作，请联系管理员。"));
    }

    private void enqueue(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (BizException exception) {
                    LOG.warn("Account email delivery failed");
                }
            });
        } catch (RejectedExecutionException exception) {
            LOG.warn("Account email queue is full");
        }
    }

    private void send(String email, String subject, String text) {
        requireEnabled();
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text);
        try {
            senders.getObject().send(message);
        } catch (MailException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "邮件发送失败，请稍后重试");
        }
    }

    private static String checkedConsoleUrl(String value) {
        URI uri = URI.create(value);
        boolean local = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid infrahub.auth.mail.console-url");
        }
        return uri.toString().replaceFirst("/+$", "");
    }
}
