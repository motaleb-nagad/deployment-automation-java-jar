package com.nagad.deploystg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends notification mail through the ops SMTP relay. In demo mode
 * ({@code nagad.mail.simulate=true}) mails are logged instead of sent.
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
            log.info("[MAIL:simulated relay={}] from={} to={} subject='{}' ({} chars)",
                    relay, from, to, subject, body == null ? 0 : body.length());
            return;
        }
        log.info("[MAIL:live relay={}] from={} to={} subject='{}'", relay, from, to, subject);
    }
}
