package com.nagad.deploystg.service;

import com.nagad.deploystg.domain.AuditLog;
import com.nagad.deploystg.repo.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the append-only compliance trail (shared audit_log table). */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Joins the caller's transaction so the audit row commits atomically with the run's record. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String verb, String target, String detail) {
        repo.save(new AuditLog(actor, verb, target, detail));
    }
}
