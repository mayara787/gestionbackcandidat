package com.psytech.proctoring.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.psytech.proctoring.config.ProctoringProperties;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Consomme une API REST externe gratuite (géolocalisation d'IP) avec RestClient, comme au TP6.
 * Par défaut : https://ipwho.is/{ip}  (réponse JSON contenant "success" et "country_code").
 * Toute panne de l'API externe est ignorée : la surveillance continue sans la règle "pays".
 */
@Service
public class GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoService.class);
    /** On n'accepte que des IP littérales (IPv4/IPv6) : l'IP finit dans une URL, donc on la valide strictement. */
    private static final Pattern IP_LITTERALE = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");

    /** Réponse JSON minimale (les autres champs sont ignorés). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeoResponse(Boolean success,
                       @JsonProperty("country_code") @JsonAlias("countryCode") String countryCode) {}

    private final RestClient client;
    private final String baseUrl;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public GeoService(ProctoringProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.client = RestClient.builder().requestFactory(factory).build();
        String url = props.geo() != null && props.geo().url() != null ? props.geo().url() : "https://ipwho.is/";
        this.baseUrl = url.endsWith("/") ? url : url + "/";
    }

    /** Code pays ISO (ex: "TN") de l'IP donnée, si l'API externe a pu répondre. */
    public Optional<String> pays(String ip) {
        if (ip == null || !IP_LITTERALE.matcher(ip).matches()) return Optional.empty();
        // IP locale (développement) : on interroge l'API sans IP, elle géolocalise alors l'IP publique de la machine.
        String cible = estLocale(ip) ? "" : ip;
        String cle = cible.isEmpty() ? "local" : cible;
        String connu = cache.get(cle);
        if (connu != null) return Optional.of(connu);
        try {
            GeoResponse r = client.get().uri(baseUrl + cible).retrieve().body(GeoResponse.class);
            if (r != null && !Boolean.FALSE.equals(r.success()) && r.countryCode() != null && !r.countryCode().isBlank()) {
                cache.put(cle, r.countryCode());
                return Optional.of(r.countryCode());
            }
        } catch (Exception e) {
            log.warn("API de géolocalisation indisponible : {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static boolean estLocale(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip); // littéral validé plus haut : aucune résolution DNS
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }
}
