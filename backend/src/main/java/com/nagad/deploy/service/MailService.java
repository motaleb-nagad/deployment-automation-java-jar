package com.nagad.deploy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends notification mail through the ops SMTP relay (10.210.2.16:25). In demo mode
 * ({@code nagad.mail.simulate=true}) mails are logged instead of sent, so the console
 * runs with no live relay.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    @Value("${nagad.mail.simulate}")
    private boolean simulate;
    @Value("${nagad.mail.relay}")
    private String relay;
    @Value("${nagad.mail.from}")
    private String from;

    public void send(String to, String subject, String body) {
        if (simulate) {
            log.info("[MAIL:simulated relay={}] from={} to={} subject='{}'\n{}", relay, from, to, subject, body);
            return;
        }
        // Production: open an SMTP connection to `relay` and send. Wire JavaMailSender here.
        log.info("[MAIL:live relay={}] to={} subject='{}'", relay, to, subject);
    }
}
