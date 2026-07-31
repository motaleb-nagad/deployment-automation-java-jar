package com.nagad.deploystg.service;

import com.nagad.deploystg.domain.Deployment;
import com.nagad.deploystg.dto.Dtos.*;
import com.nagad.deploystg.repo.DeploymentRepository;
import com.nagad.deploystg.security.StgUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.http.HttpStatus.*;

/**
 * Staging application-properties flow — surgical, per-app edits of {@code application.properties}
 * on a group's single staging host (core → ngd-dc-core-01, portal → ngd-dc-portal-01), mirroring
 * the production capability but scoped to the {@code stg-deployment} bundle. Every edit is
 * written to the shared history/audit so it shows in the staging History view.
 */
@Service
public class StgPropertiesService {

    private static final Logger log = LoggerFactory.getLogger(StgPropertiesService.class);

    private final StgInventory inv;
    private final StgPropertiesRunner runner;
    private final DeploymentRepository deployments;
    private final AuditService audit;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");
    private final AtomicInteger seq = new AtomicInteger(9500);

    public StgPropertiesService(StgInventory inv, StgPropertiesRunner runner,
                                DeploymentRepository deployments, AuditService audit) {
        this.inv = inv;
        this.runner = runner;
        this.deployments = deployments;
        this.audit = audit;
    }

    public StgPropertiesCatalog catalog() {
        List<StgPropGroupView> groups = inv.groups().stream().map(g -> new StgPropGroupView(
                g.key(), g.label(), g.host(), g.ip(),
                inv.apps(g.key()).stream().map(a -> new StgPropAppView(a.key(), a.jar(),
                        StgPropertiesRunner.cfgFile(a.key()))).toList())).toList();
        return new StgPropertiesCatalog(groups, StgPropertiesRunner.OPS, StgPropertiesRunner.BLOCK_OPS,
                StgPropertiesRunner.CFG_TEMPLATE);
    }

    public StgPropertiesResult apply(StgUser actor, StgPropertiesRequest req) {
        String group = req.group() == null ? "" : req.group().trim();
        String app = req.app() == null ? "" : req.app().trim();
        String op = req.op() == null ? "" : req.op().trim();

        if (!StgPropertiesRunner.OPS.contains(op)) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown operation " + op);
        }
        StgInventory.Group g = inv.group(group).orElseThrow(() ->
                new ResponseStatusException(BAD_REQUEST, "unknown staging group " + group + " (use: core | portal)"));
        if (!TOKEN.matcher(app).matches() || inv.jarFor(group, app).isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown app " + app + " in " + group);
        }

        boolean write = StgPropertiesRunner.WRITE_OPS.contains(op);
        boolean test = req.testMode();
        if (!write) {
            if (!actor.r()) throw new ResponseStatusException(FORBIDDEN, "reading properties needs read (r)");
        } else if (test) {
            if (!actor.w()) throw new ResponseStatusException(FORBIDDEN, "a test edit needs write (w)");
        } else {
            if (!actor.x()) throw new ResponseStatusException(FORBIDDEN, "applying a real edit needs execute (x)");
        }

        validateArgs(op, req);

        String host = g.host();
        String blockPath = runner.blockFilePath(app);
        boolean changed = write && !test;

        List<StgPropTermLine> lines;
        try {
            lines = simulate ? runner.script(host, req, blockPath, changed) : runner.execute(host, req);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("stg properties {} {}:{} failed: {}", op, host, app, e.toString());
            throw new ResponseStatusException(BAD_GATEWAY, "properties run failed on jump host: " + e.getMessage());
        }

        String cmd = runner.command(host, req, blockPath);
        recordHistory(actor.username(), group, host, app, op, test, cmd);

        String savedBlock = StgPropertiesRunner.BLOCK_OPS.contains(op) ? blockPath : null;
        return new StgPropertiesResult(cmd, host, StgPropertiesRunner.cfgFile(app),
                StgPropertiesRunner.backupFile(app), test, changed, savedBlock, lines);
    }

    /** Record the edit to the shared deployment history + audit so it shows in staging History. */
    private void recordHistory(String actor, String group, String host, String app, String op,
                               boolean test, String cmd) {
        String id = "STGP-" + seq.getAndIncrement();
        Deployment d = new Deployment(id, null, "stg-props", host, app,
                (test ? "test:" : "") + "properties-" + op, actor);
        d.complete(test ? "test" : "ok", "-", null, cmd);
        deployments.save(d);
        audit.recordSelf(actor, "stg-properties-" + op, host + ":" + app, (test ? "TEST " : "") + cmd);
    }

    private void validateArgs(String op, StgPropertiesRequest r) {
        switch (op) {
            case "update" -> {
                if (blank(r.oldLine()) || blank(r.newLine()))
                    throw new ResponseStatusException(BAD_REQUEST, "update needs old_line and new_line");
            }
            case "add_csv", "remove_csv" -> {
                if (blank(r.key()) || blank(r.values()))
                    throw new ResponseStatusException(BAD_REQUEST, op + " needs a key and value(s)");
            }
            case "rename" -> {
                if (blank(r.oldKey()) || blank(r.newKey()))
                    throw new ResponseStatusException(BAD_REQUEST, "rename needs old_key and new_key");
            }
            case "append" -> {
                if (StgPropertiesRunner.blockLines(r.block()).isEmpty())
                    throw new ResponseStatusException(BAD_REQUEST, "append needs at least one line in the block");
            }
            case "insert" -> {
                if (blank(r.afterLine()))
                    throw new ResponseStatusException(BAD_REQUEST, "insert needs the line to insert after");
                if (StgPropertiesRunner.blockLines(r.block()).isEmpty())
                    throw new ResponseStatusException(BAD_REQUEST, "insert needs at least one line in the block");
            }
            default -> { }
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
