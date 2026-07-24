package com.nagad.deploy.web;

import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.security.CurrentUser;
import com.nagad.deploy.service.ApprovalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Super-admin only. Method-level {@code @PreAuthorize} is the server-side RBAC gate. */
@RestController
@RequestMapping("/api/approvals")
@PreAuthorize("hasRole('SUPERADMIN')")
public class ApprovalController {

    private final ApprovalService approvals;
    private final CurrentUser current;

    public ApprovalController(ApprovalService approvals, CurrentUser current) {
        this.approvals = approvals;
        this.current = current;
    }

    @GetMapping("/pending")
    public List<PromotionView> pending() {
        return approvals.pending();
    }

    @GetMapping("/decided")
    public List<PromotionView> decided() {
        return approvals.decided();
    }

    @PostMapping("/{id}/approve")
    public PromotionView approve(@PathVariable String id) {
        return approvals.approve(current.require(), id);
    }

    @PostMapping("/{id}/deny")
    public PromotionView deny(@PathVariable String id, @RequestBody DenyRequest req) {
        return approvals.deny(current.require(), id, req.reason());
    }
}
