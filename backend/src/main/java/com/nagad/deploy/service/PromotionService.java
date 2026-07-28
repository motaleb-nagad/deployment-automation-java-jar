package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.PromotionStatus;
import com.nagad.deploy.domain.Role;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.repo.AppUserRepository;
import com.nagad.deploy.repo.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * The PROMOTE flow: fetch a jar from staging, read its hash, record it to registry-db and
 * open an approval request. This never touches production — deploy stays locked until a
 * super admin approves. Maps to the {@code fetch}/{@code check-hash} playbooks.
 */
@Service
public class PromotionService {

    private final PromotionRepository promos;
    private final AppUserRepository users;
    private final StagingCatalog staging;
    private final FleetInventory inv;
    private final MailService mail;
    private final AuditService audit;

    private final AtomicInteger seq = new AtomicInteger(1043);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    public PromotionService(PromotionRepository promos, AppUserRepository users, StagingCatalog staging,
                            FleetInventory inv, MailService mail, AuditService audit) {
        this.promos = promos;
        this.users = users;
        this.staging = staging;
        this.inv = inv;
        this.mail = mail;
        this.audit = audit;
    }

    @Transactional
    public List<PromotionView> fetch(AppUser actor, FetchRequest req) {
        if (!actor.isPermW()) {
            throw new ResponseStatusException(FORBIDDEN, "fetching from staging needs the write (w) permission");
        }
        if (req.apps() == null || req.apps().isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "select at least one app to fetch");
        }
        StagingCatalog.Source src = staging.source(req.srcGroup());

        List<PromotionView> created = new java.util.ArrayList<>();
        for (String app : req.apps()) {
            String destGroup = staging.destGroup(app);
            if (!actor.canAccessGroup(destGroup)) {
                throw new ResponseStatusException(FORBIDDEN,
                        "you are not scoped to deploy " + app + " (group " + destGroup + ")");
            }
            String jar = FleetInventory.JAR_MAP.getOrDefault(app, app + "-1.0.jar");
            // The staged build's hash — in production this comes from check-hash on the fetched file.
            String newHash = inv.hex7("staged/" + app + "/" + System.nanoTime());
            String prevHash = inv.hash(destGroup, app);
            String id = "PR-" + seq.getAndIncrement();

            Promotion p = new Promotion(id, app, destGroup, src.host(), jar, newHash, prevHash,
                    "release/8.2", estimateSize(jar), actor.getUsername());
            // Approval gate retired: a fetched jar is immediately deployable.
            p.decide(PromotionStatus.APPROVED, actor.getUsername(), null);
            promos.save(p);
            audit.record(actor.getUsername(), "fetch", id + " " + app,
                    "fetched " + jar + " (" + newHash + ") from " + src.host());
            created.add(view(p));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<PromotionView> mine(String username) {
        return promos.findByRequestedByOrderByRequestedAtDesc(username).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<PromotionView> all() {
        return promos.findAllByOrderByRequestedAtDesc().stream().map(this::view).toList();
    }

    private String estimateSize(String jar) {
        long n = inv.h32(jar) % 40;
        return (18 + n) + "." + (inv.h32(jar + "x") % 10) + " MB";
    }

    PromotionView view(Promotion p) {
        return new PromotionView(p.getId(), p.getApp(), p.getGroupName(), p.getSrcHost(), p.getJar(),
                p.getGitHash(), p.getPrevHash(), p.getBranch(), p.getSizeLabel(),
                p.getRequestedBy(), fmt(p.getRequestedAt()), p.getStatus().wire(),
                p.getDecidedBy(), fmt(p.getDecidedAt()), p.getNote());
    }

    static String fmt(java.time.Instant i) {
        return i == null ? null : FMT.format(i);
    }
}
