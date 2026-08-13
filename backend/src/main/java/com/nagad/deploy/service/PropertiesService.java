package com.nagad.deploy.service;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.dto.Dtos.*;
import com.nagad.deploy.service.FleetInventory.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.*;

/**
 * The APPLICATION-PROPERTIES flow — surgical, in-place editing of a service's
 * {@code application.properties} on a production host, mirroring {@code properties.sh} /
 * {@code properties.yml}. Like Deploy, it targets a single host + app; unlike a full-file
 * upload it edits one line / block. A pasted block (append / insert) is staged to a file on
 * the server (a previous one removed, a fresh one created) before the wrapper runs.
 */
@Service
public class PropertiesService {

    private static final Logger log = LoggerFactory.getLogger(PropertiesService.class);

    private final FleetInventory inv;
    private final PropertiesRunner runner;
    private final AuditService audit;

    @Value("${nagad.ansible.simulate}")
    private boolean simulate;

    // Shell-safe token for host/app; matches the DeploymentService whitelist.
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]+$");

    public PropertiesService(FleetInventory inv, PropertiesRunner runner, AuditService audit) {
        this.inv = inv;
        this.runner = runner;
        this.audit = audit;
    }

    /** The host/app tree (managed groups, like Deploy) plus the operations the screen offers. */
    public PropertiesCatalog catalog() {
        List<PropGroupView> groups = new ArrayList<>();
        for (Group g : inv.managedGroups()) {
            List<PropAppView> apps = g.svcs().stream()
                    .map(s -> new PropAppView(s.key(), s.jar(), PropertiesRunner.cfgFile(s.key())))
                    .toList();
            groups.add(new PropGroupView(g.key(), g.cmd(), g.zone(), g.tier(),
                    g.hosts().stream().map(FleetInventory.Host::name).toList(), apps));
        }
        return new PropertiesCatalog(groups, PropertiesRunner.OPS, PropertiesRunner.BLOCK_OPS,
                PropertiesRunner.CFG_TEMPLATE);
    }

    /**
     * Validate and run a properties edit. Permission model, mirroring RBAC:
     * read-only ops (preview / diff) need read (r); any mutating op in {@code --test} mode
     * needs write (w); a real (non-test) mutating op needs execute (x) — editing live prod
     * config is an execute-class action, exactly like a deploy.
     */
    public PropertiesResult apply(AppUser actor, PropertiesRequest req) {
        String app = req.app() == null ? "" : req.app().trim();
        String op = req.op() == null ? "" : req.op().trim();
        List<String> hosts = new ArrayList<>();
        if (req.hosts() != null) {
            for (String h : req.hosts()) {
                String t = h == null ? "" : h.trim();
                if (!t.isEmpty() && !hosts.contains(t)) hosts.add(t);
            }
        }

        if (!PropertiesRunner.OPS.contains(op)) {
            throw new ResponseStatusException(BAD_REQUEST, "unknown operation " + op);
        }
        if (hosts.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "select at least one host");
        }
        if (!TOKEN.matcher(app).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid app");
        }
        // Resolve the group from the first host; every host must be in that same group and the
        // group must run this app (one service across one or more of its hosts).
        Group hg = inv.groupForHost(hosts.get(0)).orElseThrow(() ->
                new ResponseStatusException(BAD_REQUEST, "unknown host " + hosts.get(0)));
        if (hg.svcs().stream().noneMatch(s -> s.key().equals(app))) {
            throw new ResponseStatusException(BAD_REQUEST, "app " + app + " is not in group " + hg.cmd());
        }
        java.util.Set<String> groupHosts = hg.hosts().stream()
                .map(FleetInventory.Host::name).collect(java.util.stream.Collectors.toSet());
        for (String h : hosts) {
            if (!TOKEN.matcher(h).matches() || !groupHosts.contains(h)) {
                throw new ResponseStatusException(BAD_REQUEST, "host " + h + " is not in group " + hg.cmd());
            }
        }
        if (!actor.canAccessGroup(hg.cmd())) {
            throw new ResponseStatusException(FORBIDDEN, "you are not scoped to group " + hg.cmd());
        }

        boolean write = PropertiesRunner.WRITE_OPS.contains(op);
        boolean test = req.testMode();
        if (!write) {
            if (!actor.isPermR()) throw new ResponseStatusException(FORBIDDEN, "reading properties needs read (r)");
        } else if (test) {
            if (!actor.isPermW()) throw new ResponseStatusException(FORBIDDEN, "a test edit needs write (w)");
        } else {
            if (!actor.isPermX()) throw new ResponseStatusException(FORBIDDEN,
                    "applying a real properties edit needs execute (x)");
        }

        validateArgs(op, req);

        // Re-pack the request with the cleaned host list so the runner sees clean values.
        PropertiesRequest r = new PropertiesRequest(hosts, app, op, test,
                req.oldLine(), req.newLine(), req.key(), req.values(), req.oldKey(), req.newKey(),
                req.afterLine(), req.block());
        String blockPath = runner.blockFilePath(app);
        boolean changed = write && !test;

        List<PropTermLine> lines;
        try {
            lines = simulate ? runner.script(r, blockPath, changed) : runner.execute(r);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("properties {} {}:{} failed: {}", op, hosts, app, e.toString());
            throw new ResponseStatusException(BAD_GATEWAY, "properties run failed on jump host: " + e.getMessage());
        }

        String savedBlock = null; // block ops now pass lines inline — no temp file is staged
        String cmd = runner.command(String.join(",", hosts), r, blockPath);
        // No surrounding business transaction (the run itself must not hold one, especially a
        // real SSH run) — use the standalone audit write.
        audit.recordSelf(actor.getUsername(), "properties-" + op, String.join(",", hosts) + ":" + app,
                (test ? "TEST " : "") + cmd);

        return new PropertiesResult(cmd, PropertiesRunner.cfgFile(app),
                PropertiesRunner.backupFile(app), test, changed, savedBlock, lines);
    }

    private void validateArgs(String op, PropertiesRequest r) {
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
                if (PropertiesRunner.blockLines(r.block()).isEmpty())
                    throw new ResponseStatusException(BAD_REQUEST, "append needs at least one line in the block");
            }
            case "insert" -> {
                if (blank(r.afterLine()))
                    throw new ResponseStatusException(BAD_REQUEST, "insert needs the line to insert after");
                if (PropertiesRunner.blockLines(r.block()).isEmpty())
                    throw new ResponseStatusException(BAD_REQUEST, "insert needs at least one line in the block");
            }
            default -> { /* preview / diff / restore — no required args */ }
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
