package com.nagad.deploy.service;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The inventory model behind the fleet dashboard — groups → hosts → services, mirroring
 * the Ansible {@code hosts} file and the {@code jar_map}. Status/hash/uptime are computed
 * deterministically here to stand in for the every-few-minutes collector; swap this for a
 * real collector feed (or an SSH {@code pstat} sweep) without touching the API shape.
 */
@Component
public class FleetInventory {

    public record Svc(String key, int instances, String jar) {}
    public record Host(String name, String ip) {}
    public record Group(String key, String cmd, String zone, String tier, List<Host> hosts, List<Svc> svcs) {}

    /** app key → jar name (the jar_map, keyed by app type not host/user). */
    public static final Map<String, String> JAR_MAP = jarMap();

    private final List<Group> groups = build();

    public List<Group> groups() { return groups; }

    public Optional<Group> group(String key) {
        return groups.stream().filter(g -> g.key().equals(key)).findFirst();
    }

    /** Wrapper groups the action panel / promote flow can target (managed by run.sh). */
    public List<Group> managedGroups() {
        return groups.stream().filter(g -> g.cmd() != null).toList();
    }

    /**
     * Demo stand-in for "is this service actually installed on this host?" — the truth a real
     * run learns from {@code consolidated.yml}'s availability pre-check. A few services are
     * host-restricted (e.g. the portal_* apps only run on the txn-history tier), so selecting
     * them on another host demonstrates the skip-and-continue behaviour without touching hosts.
     * Every other app is treated as present on any host it can be targeted on.
     */
    private static final Map<String, java.util.Set<String>> HOST_RESTRICTED = Map.of(
            "portal_davs", java.util.Set.of("app11", "app12", "app13"),
            "portal_dfs",  java.util.Set.of("app11", "app12", "app13"));

    public boolean demoPresentOnHost(String host, String app) {
        java.util.Set<String> allowed = HOST_RESTRICTED.get(app);
        return allowed == null || allowed.contains(host);
    }

    /** The managed group a production host belongs to — used to route consolidated host:app pairs. */
    public Optional<Group> groupForHost(String host) {
        return managedGroups().stream()
                .filter(g -> g.hosts().stream().anyMatch(h -> h.name().equals(host)))
                .findFirst();
    }

    /** Every host across managed groups (consolidated targets can span all of them). */
    public List<Host> managedHosts() {
        return managedGroups().stream().flatMap(g -> g.hosts().stream()).toList();
    }

    // ---- deterministic collector stand-ins (FNV-1a, matching the prototype) ----

    public long h32(String s) {
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xff);
            h = (h * 16777619L) & 0xffffffffL;
        }
        return h;
    }

    public String hex7(String s) {
        return (Long.toHexString(h32(s)) + Long.toHexString(h32(s + "~"))).substring(0, 7);
    }

    public String hash(String group, String svc) {
        return hex7(group + "/" + svc);
    }

    public long pid(String s) {
        return 1200 + h32(s) % 58000;
    }

    public String uptime(String group, String host, String svc) {
        long n = h32(group + host + svc);
        return (3 + n % 9) + "d " + pad(n % 24) + ":" + pad((n >> 3) % 60);
    }

    private static String pad(long v) {
        return String.format("%02d", v);
    }

    private static Map<String, String> jarMap() {
        Map<String, String> j = new LinkedHashMap<>();
        j.put("apigw", "api-gateway-1.0.jar");
        j.put("apigw-summary", "api-gateway-1.0.jar");
        j.put("apigwext", "api-gatewayext-1.0.jar");
        j.put("bds", "bulk-disbursement-service-1.0.jar");
        j.put("bkofc", "back-office-service-1.0.jar");
        j.put("bkofc-summary", "back-office-service-1.0.jar");
        j.put("cms", "cms-1.0.jar");
        j.put("cp", "cloud-platform-1.0.jar");
        j.put("cs", "charge-service-1.0.jar");
        j.put("davs", "data-analytics-visualization-system-1.0.jar");
        j.put("dfs", "mobile-banking-service-1.0.jar");
        j.put("dmscore", "dms-portal-core-1.0.jar");
        j.put("drs", "dispute-resolution-service-1.0.jar");
        j.put("ecs", "e-commerce-service-1.0.jar");
        j.put("extch", "external-channel-1.0.jar");
        j.put("extchSMS", "external-channel-1.0.jar");
        j.put("ias", "ias-1.0.jar");
        j.put("kod", "kod-1.0.jar");
        j.put("knotify", "knotify-1.0.jar");
        j.put("map", "mobile-platform-1.0.jar");
        j.put("mps", "merchant-payout-system-1.0.jar");
        j.put("npsb_recon", "npsb-reconciliation-1.0.0.jar");
        j.put("npsb_parser", "npsb-parser-1.0.0.jar");
        j.put("pcs", "prepaid-card-service-1.0.jar");
        j.put("png", "payment-network-gateway.jar");
        j.put("pp", "payment-processor-1.0.jar");
        j.put("portal_davs", "data-analytics-visualization-system-1.0.jar");
        j.put("portal_dfs", "mobile-banking-service-1.0.jar");
        j.put("rms", "reward-management-service-1.0.jar");
        j.put("rpg", "remote-payment-gateway-server-1.0.jar");
        j.put("spg", "secure-payment-gateway-1.0.jar");
        j.put("tms", "transaction-management-system-1.0.jar");
        j.put("tsp", "token-service-provider-1.0.jar");
        j.put("utilityservice", "kona-utility-1.0.jar");
        j.put("syscore", "system-portal-core-1.0.jar");
        j.put("callcentercore", "call-center-portal-core-1.0.jar");
        j.put("accs", "disbursement-manager-1.0.jar");
        j.put("auth", "authentication-server-1.0.jar");
        j.put("dmsgw", "portal-gateway-1.0.jar");
        j.put("sysgw", "portal-gateway-1.0.jar");
        j.put("callcentergw", "portal-gateway-1.0.jar");
        j.put("rpgweb", "rpg-web-1.0.jar");
        j.put("ussdgwrobi", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwttalk", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwblink", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwgp", "ussd-api-gateway-1.0.jar");
        j.put("outboundproxy", "outboundproxy-1.0.jar");
        j.put("knotifypush", "knotify-1.0.jar");
        return j;
    }

    private static final List<String> CORE_APPS = List.of(
            "apigw", "apigw-summary", "apigwext", "bds", "bkofc", "bkofc-summary", "cms", "cp", "cs",
            "davs", "dfs", "dmscore", "drs", "ecs", "extch", "extchSMS", "ias", "kod", "knotify",
            "map", "mps", "npsb_recon", "pcs", "portal_davs", "portal_dfs", "rms", "rpg", "spg",
            "tms", "tsp", "utilityservice");

    private static Svc svc(String key) {
        int inst = switch (key) {
            case "apigw" -> 3;
            case "apigw-summary" -> 2;
            default -> 1;
        };
        return new Svc(key, inst, JAR_MAP.getOrDefault(key, ""));
    }

    private static List<Svc> svcs(List<String> keys) {
        return keys.stream().map(FleetInventory::svc).toList();
    }

    private static List<Host> seq(String prefix, int a, int b, String net, int o1) {
        List<Host> out = new ArrayList<>();
        for (int i = a; i <= b; i++) out.add(new Host(prefix + i, net + "." + (o1 + i - a)));
        return out;
    }

    private static List<Group> build() {
        List<Group> g = new ArrayList<>();
        g.add(new Group("nagad-app", "core", "DC", "core app tier · nagad-app1–6",
                seq("app", 1, 6, "10.220.2", 11), svcs(CORE_APPS)));
        g.add(new Group("nagad-app-dkyc", "core", "DC", "dkyc tier · via core.yml",
                seq("app", 7, 8, "10.220.2", 17), svcs(List.of("apigw", "dmscore"))));
        g.add(new Group("nagad-app-limited", "core", "DC", "limited tier · via core.yml",
                seq("app", 9, 10, "10.220.2", 19), svcs(List.of("apigw", "knotify"))));
        g.add(new Group("nagad-app-txn-history", "core", "DC", "txn-history tier · via core.yml",
                seq("app", 11, 13, "10.220.2", 51),
                svcs(List.of("apigw", "apigw-summary", "dfs", "davs", "extch", "bkofc-summary", "portal_davs", "portal_dfs"))));
        g.add(new Group("nagad-web", "web", "DC", "portal tier · web.yml",
                seq("web", 1, 3, "10.220.2", 21), svcs(List.of("dmscore", "syscore", "callcentercore", "accs", "auth"))));
        g.add(new Group("nagad-knotifypush", "knotifypush", "DC", "push tier · user knotifypush",
                seq("knotifypush", 1, 3, "10.220.2", 126), svcs(List.of("knotifypush"))));
        g.add(new Group("nagad-web-dmz", "web-dmz", "DMZ", "DMZ portal gateways · web-dmz.yml",
                seq("dmz-web", 1, 3, "10.210.2", 70), svcs(List.of("dmsgw", "sysgw", "callcentergw", "rpgweb"))));
        g.add(new Group("nagad-ussd", "ussd", "DMZ", "USSD gateways · ussd.yml",
                List.of(new Host("ussd1", "10.210.2.20"), new Host("ussd2", "10.210.2.21"),
                        new Host("ussd3", "10.210.2.22"), new Host("ussd4", "10.210.2.23"),
                        new Host("ussd5", "10.210.2.52"), new Host("ussd6", "10.210.2.53")),
                svcs(List.of("ussdgwrobi", "ussdgwttalk", "ussdgwblink", "ussdgwgp", "outboundproxy"))));
        g.add(new Group("nagad-apigwdoc", "apigwdoc", "DMZ", "doc API gateway · user apigw · 3 INST",
                seq("apigwdoc", 1, 3, "10.210.10", 61), List.of(new Svc("apigw", 3, JAR_MAP.get("apigw")))));
        // NPSB tiers — each backed by its own npsb-*.yml playbook (src 10.220.2.46, port 40167).
        g.add(new Group("nagad-npsb-apigw", "npsb-apigw", "DMZ", "NPSB API gateway · npsb-apigw1–2",
                seq("npsb-apigw", 1, 2, "10.210.10", 135), svcs(List.of("apigw"))));
        g.add(new Group("nagad-npsb-spg", "npsb-spg", "DMZ", "NPSB secure payment gateway · npsb-spg1–2",
                seq("npsb-spg", 1, 2, "10.220.10", 210), svcs(List.of("spg"))));
        g.add(new Group("nagad-npsb-pp", "npsb-pp", "DC", "NPSB payment processor + parser · npsb-pp1–2",
                seq("npsb-pp", 1, 2, "10.220.10", 218), svcs(List.of("pp", "npsb_parser"))));
        g.add(new Group("nagad-npsb-png", "npsb-png", "DMZ", "NPSB payment network gateway · npsb-png1",
                seq("npsb-png", 1, 1, "10.210.10", 37), svcs(List.of("png"))));
        // Staging sources (not managed by run.sh — promotion sources only).
        g.add(new Group("staging-core", null, "STG", "staging — promotion source",
                List.of(new Host("ngd-dc-core-01", "10.230.1.208")), List.of()));
        g.add(new Group("staging-web", null, "STG", "staging — promotion source",
                List.of(new Host("ngd-dc-portal-01", "10.230.1.207")), List.of()));
        return g;
    }
}
