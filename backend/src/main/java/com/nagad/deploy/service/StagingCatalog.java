package com.nagad.deploy.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Staging sources and the built jars available on each — the input to a promotion. */
@Component
public class StagingCatalog {

    public record Source(String key, String host, String ip, List<String> apps) {}

    private final List<Source> sources = List.of(
            new Source("staging-core", "ngd-dc-core-01", "10.230.1.208",
                    List.of("spg", "apigw", "apigw-summary", "map", "dfs", "bkofc", "npsb_recon",
                            "cms", "cp", "cs", "mps", "tms", "tsp", "rms", "ecs", "drs", "bds", "kod", "knotify")),
            new Source("staging-web", "ngd-dc-portal-01", "10.230.1.207",
                    List.of("dmscore", "syscore", "callcentercore", "accs", "auth",
                            "dmsgw", "sysgw", "callcentergw", "rpgweb")));

    private static final Map<String, String> DEST_OVERRIDE = Map.ofEntries(
            Map.entry("ussdgwrobi", "ussd"), Map.entry("ussdgwttalk", "ussd"),
            Map.entry("ussdgwblink", "ussd"), Map.entry("ussdgwgp", "ussd"), Map.entry("outboundproxy", "ussd"),
            Map.entry("dmsgw", "web-dmz"), Map.entry("sysgw", "web-dmz"),
            Map.entry("callcentergw", "web-dmz"), Map.entry("rpgweb", "web-dmz"),
            Map.entry("dmscore", "web"), Map.entry("syscore", "web"),
            Map.entry("callcentercore", "web"), Map.entry("accs", "web"), Map.entry("auth", "web"));

    public List<Source> sources() { return sources; }

    public Source source(String key) {
        return sources.stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown staging source: " + key));
    }

    /** Which wrapper group this app deploys to in production. */
    public String destGroup(String app) {
        return DEST_OVERRIDE.getOrDefault(app, "core");
    }
}
