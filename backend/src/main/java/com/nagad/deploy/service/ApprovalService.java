package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.PromotionStatus;
import com.nagad.deploy.dto.Dtos.PromotionView;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.repo.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** The super-admin approval gate. Nothing reaches production without a decision here. */
@Service
public class ApprovalService {

    private final PromotionRepository promos;
    private final AppUserRepository users;
    private final PromotionService promotionService;
    private final MailService mail;
    private final AuditService audit;

    public ApprovalService(PromotionRepository promos, AppUserRepository users,
                           PromotionService promotionService, MailService mail, AuditService audit) {
        this.promos = promos;
        this.users = users;
        this.promotionService = promotionService;
        this.mail = mail;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PromotionView> pending() {
        return promos.findByStatusOrderByRequestedAtDesc(PromotionStatus.PENDING)
                .stream().map(promotionService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<PromotionView> decided() {
        return promos.findAllByOrderByRequestedAtDesc().stream()
                .filter(p -> p.getStatus() != PromotionStatus.PENDING)
                .map(promotionService::view).toList();
    }

    @Transactional
    public PromotionView approve(AppUser admin, String id) {
        Promotion p = load(id);
        requirePending(p);
        p.decide(PromotionStatus.APPROVED, admin.getUsername(), null);
        audit.record(admin.getUsername(), "approve", id + " " + p.getApp(),
                "approved " + p.getJar() + " (" + p.getGitHash() + ") for " + p.getGroupName());
        notifyRequester(p, "approved",
                "Your promotion " + id + " (" + p.getApp() + ") was approved. It is now deployable.");
        return promotionService.view(p);
    }

    @Transactional
    public PromotionView deny(AppUser admin, String id, String reason) {
        Promotion p = load(id);
        requirePending(p);
        p.decide(PromotionStatus.DENIED, admin.getUsername(), reason);
        audit.record(admin.getUsername(), "deny", id + " " + p.getApp(),
                reason == null ? "denied" : reason);
        notifyRequester(p, "denied",
                "Your promotion " + id + " (" + p.getApp() + ") was denied."
                        + (reason == null ? "" : " Reason: " + reason));
        return promotionService.view(p);
    }

    private Promotion load(String id) {
        return promos.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "no such request"));
    }

    private void requirePending(Promotion p) {
        if (p.getStatus() != PromotionStatus.PENDING) {
            throw new ResponseStatusException(CONFLICT, "request already " + p.getStatus().wire());
        }
    }

    private void notifyRequester(Promotion p, String verb, String body) {
        users.findById(p.getRequestedBy()).ifPresent(u ->
                mail.send(u.getEmail(), "Promotion " + p.getId() + " " + verb, body));
    }
}
