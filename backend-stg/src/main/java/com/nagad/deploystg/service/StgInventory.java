package com.nagad.deploystg.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inventory model for the <strong>staging</strong> deployment bundle ({@code stg-deployment/}).
 * Mirrors the wrapper's {@code hosts}, the {@code jar_map} in {@code core.yml} / {@code portal.yml},
 * and the portal-UI {@code valid_uis} list. One host per group:
 * <ul>
 *   <li>{@code core}  → stg-core  (ngd-dc-core-01, 10.230.1.208)</li>
 *   <li>{@code portal} → stg-portal (ngd-dc-portal-01, 10.230.1.207)</li>
 * </ul>
 */
@Component
public class StgInventory {

    public record App(String key, String jar, String host, String ip) {}
    /** {@code appHosts} maps an app → {host, ip} for groups whose services live on different
     *  hosts (npsb-zone). Empty for single-host groups (core/portal), which use {@code host}/{@code ip}. */
    public record Group(String key, String label, String host, String ip,
                        Map<String, String> jarMap, Map<String, String[]> appHosts) {}

    /** Portal-UI names the staging portalui/run.sh accepts (each needs a matching {@code <ui>.tar}). */
    public static final List<String> UIS = List.of("system", "dms", "call-center", "operations");

    private final Map<String, Group> groups = build();

    public List<Group> groups() {
        return List.copyOf(groups.values());
    }

    public Optional<Group> group(String key) {
        return Optional.ofNullable(groups.get(key));
    }

    public List<App> apps(String groupKey) {
        Group g = groups.get(groupKey);
        if (g == null) return List.of();
        return g.jarMap().entrySet().stream().map(e -> {
            String[] hp = g.appHosts().getOrDefault(e.getKey(), new String[]{g.host(), g.ip()});
            return new App(e.getKey(), e.getValue(), hp[0], hp[1]);
        }).toList();
    }

    public Optional<String> jarFor(String groupKey, String app) {
        Group g = groups.get(groupKey);
        if (g == null) return Optional.empty();
        return Optional.ofNullable(g.jarMap().get(app));
    }

    /** The host an app is deployed to within a group (per-app for npsb-zone, else the group host). */
    public String hostFor(String groupKey, String app) {
        Group g = groups.get(groupKey);
        if (g == null) return "";
        String[] hp = g.appHosts().get(app);
        return hp != null ? hp[0] : g.host();
    }

    public boolean isValidUi(String ui) {
        return UIS.contains(ui);
    }

    private static Map<String, Group> build() {
        Map<String, Group> g = new LinkedHashMap<>();
        g.put("core", new Group("core", "STG CORE — ngd-dc-core-01",
                "ngd-dc-core-01", "10.230.1.208", coreJarMap(), Map.of()));
        g.put("portal", new Group("portal", "STG PORTAL — ngd-dc-portal-01",
                "ngd-dc-portal-01", "10.230.1.207", portalJarMap(), Map.of()));
        // NPSB zone: one service per host across four staging servers.
        g.put("npsb-zone", new Group("npsb-zone", "STG NPSB ZONE — apigw / spg / png / pp",
                "npsb-zone", "", npsbJarMap(), npsbHosts()));
        return g;
    }

    /** jar_map for the NPSB staging zone (npsb-zone). */
    private static Map<String, String> npsbJarMap() {
        Map<String, String> j = new LinkedHashMap<>();
        j.put("apigw", "api-gateway-1.0.jar");
        j.put("spg", "secure-payment-gateway-1.0.jar");
        j.put("png", "payment-network-gateway.jar");
        j.put("pp", "payment-processor-1.0.jar");
        j.put("npsb_parser", "npsb-parser-1.0.0.jar");
        return j;
    }

    /** Per-app staging host for npsb-zone: app → {host, ip}. */
    private static Map<String, String[]> npsbHosts() {
        Map<String, String[]> h = new LinkedHashMap<>();
        h.put("apigw",       new String[]{"npsb-apigw", "10.210.24.211"});
        h.put("spg",         new String[]{"npsb-spg", "10.220.24.210"});
        h.put("png",         new String[]{"kona-switch", "10.210.24.136"});
        h.put("pp",          new String[]{"npsb-pp", "10.220.24.212"});
        h.put("npsb_parser", new String[]{"npsb-pp", "10.220.24.212"});
        return h;
    }

    /** jar_map from stg-deployment/core.yml (stg-core). */
    private static Map<String, String> coreJarMap() {
        Map<String, String> j = new LinkedHashMap<>();
        j.put("apigw", "api-gateway-1.0.jar");
        j.put("apigwext", "api-gatewayext-1.0.jar");
        j.put("cms", "cms-1.0.jar");
        j.put("cp", "cloud-platform-1.0.jar");
        j.put("cs", "charge-service-1.0.jar");
        j.put("dfs", "mobile-banking-service-1.0.jar");
        j.put("extch", "external-channel-1.0.jar");
        j.put("extch-SMS", "external-channel-1.0.jar");
        j.put("ias", "ias-1.0.jar");
        j.put("knotify", "knotify-1.0.jar");
        j.put("kod", "kod-1.0.jar");
        j.put("map", "mobile-platform-1.0.jar");
        j.put("pcs", "prepaid-card-service-1.0.jar");
        j.put("portal_dfs", "mobile-banking-service-1.0.jar");
        j.put("portal_davs", "data-analytics-visualization-system-1.0.jar");
        j.put("rpg", "remote-payment-gateway-server-1.0.jar");
        j.put("rpgweb", "rpg-web-1.0.jar");
        j.put("rms", "reward-management-service-1.0.jar");
        j.put("drs", "dispute-resolution-service-1.0.jar");
        j.put("outboundproxy", "outboundproxy-1.0.jar");
        j.put("ussdgwblink", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwgp", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwrobi", "ussd-api-gateway-1.0.jar");
        j.put("ussdgwttalk", "ussd-api-gateway-1.0.jar");
        j.put("utilityservice", "kona-utility-1.0.jar");
        j.put("tms", "transaction-management-system-1.0.jar");
        j.put("tsp", "token-service-provider-1.0.jar");
        j.put("bkofc", "back-office-service-1.0.jar");
        j.put("bkofc-summary", "back-office-service-1.0.jar");
        j.put("spg", "secure-payment-gateway-1.0.jar");
        j.put("bds", "bulk-disbursement-service-1.0.jar");
        j.put("ecs", "e-commerce-service-1.0.jar");
        j.put("davs", "data-analytics-visualization-system-1.0.jar");
        j.put("txn_apigw", "api-gateway-1.0.jar");
        j.put("bp_apigw", "api-gateway-1.0.jar");
        j.put("core_spg", "secure-payment-gateway-1.0.jar");
        j.put("core_dfs", "mobile-banking-service-1.0.jar");
        j.put("mps", "merchant-payout-system-1.0.jar");
        j.put("npsb_recon", "npsb-reconciliation-1.0.0.jar");
        j.put("dmscore", "dms-portal-core-1.0.jar");
        return j;
    }

    /** jar_map from stg-deployment/portal.yml (stg-portal). */
    private static Map<String, String> portalJarMap() {
        Map<String, String> j = new LinkedHashMap<>();
        j.put("auth", "authentication-server-1.0.jar");
        j.put("syscore", "system-portal-core-1.0.jar");
        j.put("sysgw", "portal-gateway-1.0.jar");
        j.put("dmscore", "dms-portal-core-1.0.jar");
        j.put("dmsgw", "portal-gateway-1.0.jar");
        j.put("callcentercore", "call-center-portal-core-1.0.jar");
        j.put("callcentergw", "portal-gateway-1.0.jar");
        j.put("report", "report-service-1.0.jar");
        return j;
    }
}
