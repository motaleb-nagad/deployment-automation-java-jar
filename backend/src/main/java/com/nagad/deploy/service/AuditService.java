package com.nagad.deploy.service;

import com.nagad.deploy.domain.AuditLog;
import com.nagad.deploy.repo.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Writes the append-only compliance trail. Every fetch/approve/deny/deploy lands here. */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * Joins the caller's transaction so the audit row commits atomically with the state
     * change it records — a rolled-back action leaves no orphan audit entry, and a
     * committed action can never be missing one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String verb, String target, String detail) {
        repo.save(new AuditLog(actor, verb, target, detail));
    }

    /**
     * Standalone audit write for events that have no surrounding business transaction
     * (e.g. login). Runs in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSelf(String actor, String verb, String target, String detail) {
        repo.save(new AuditLog(actor, verb, target, detail));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> recent() {
        return repo.findTop200ByOrderByTsDesc();
    }
}
