package com.nagad.deploy.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Staging sources and the built jars available on each — the input to a promotion. */
@Component
public class StagingCatalog {

    /** A promotion source. {@code appHost} maps an app → the staging host that built it, for
     *  sources whose jars live on different hosts (staging-npsb); empty means all apps are on
     *  {@code host} (staging-core / staging-web). */
    public record Source(String key, String host, String ip, List<String> apps, Map<String, String> appHost) {}

    private final List<Source> sources = List.of(
            new Source("staging-core", "ngd-dc-core-01", "10.230.1.208",
                    List.of("spg", "apigw", "apigw-summary", "map", "dfs", "bkofc", "npsb_recon",
                            "cms", "cp", "cs", "mps", "tms", "tsp", "rms", "ecs", "drs", "bds", "kod", "knotify"),
                    Map.of()),
            new Source("staging-web", "ngd-dc-portal-01", "10.230.1.207",
                    List.of("dmscore", "syscore", "callcentercore", "accs", "auth",
                            "dmsgw", "sysgw", "callcentergw", "rpgweb"),
                    Map.of()),
            // NPSB zone — each jar is built on its own staging server.
            new Source("staging-npsb", "npsb-zone", "—",
                    List.of("apigw", "spg", "png", "pp", "npsb_parser"),
                    Map.ofEntries(
                            Map.entry("apigw", "npsb-apigw"),        // 10.210.24.211
                            Map.entry("spg", "npsb-spg"),            // 10.220.24.210
                            Map.entry("png", "kona-switch"),         // 10.210.24.136
                            Map.entry("pp", "npsb-pp"),              // 10.220.24.212
                            Map.entry("npsb_parser", "npsb-pp"))));  // 10.220.24.212

    private static final Map<String, String> DEST_OVERRIDE = Map.ofEntries(
            Map.entry("ussdgwrobi", "ussd"), Map.entry("ussdgwttalk", "ussd"),
            Map.entry("ussdgwblink", "ussd"), Map.entry("ussdgwgp", "ussd"), Map.entry("outboundproxy", "ussd"),
            Map.entry("dmsgw", "web-dmz"), Map.entry("sysgw", "web-dmz"),
            Map.entry("callcentergw", "web-dmz"), Map.entry("rpgweb", "web-dmz"),
            Map.entry("dmscore", "web"), Map.entry("syscore", "web"),
            Map.entry("callcentercore", "web"), Map.entry("accs", "web"), Map.entry("auth", "web"),
            // NPSB tiers (apigw/spg stay on core; the npsb-only apps route to their own groups).
            Map.entry("png", "npsb-png"), Map.entry("pp", "npsb-pp"), Map.entry("npsb_parser", "npsb-pp"));

    public List<Source> sources() { return sources; }

    public Source source(String key) {
        return sources.stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown staging source: " + key));
    }

    /** The staging host that built an app's jar within a source (per-app for staging-npsb). */
    public String hostFor(Source src, String app) {
        return src.appHost().getOrDefault(app, src.host());
    }

    /** Which wrapper group this app deploys to in production. */
    public String destGroup(String app) {
        return DEST_OVERRIDE.getOrDefault(app, "core");
    }
}
