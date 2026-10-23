/**
 * Role : classer les anomalies detectees dans les journaux.
 * Contenu : definition de AnomalyCategorizer et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classification des erreurs par type (cahier des charges §5.1) à partir de
 * mots-clés du message et de la trace de pile. Règles ordonnées : la première
 * catégorie qui correspond l'emporte.
 */
public final class AnomalyCategorizer {

    private static final Map<String, List<String>> RULES = new LinkedHashMap<>();

    static {
        RULES.put("SECURITY", List.of("token", "signature", "unauthorized", "forbidden", "authentication",
                "access denied", "csrf", "jwt", "certificate", "ssl", "tls", "credential", "injection", "401", "403"));
        RULES.put("DATABASE", List.of("database", "sql", "jdbc", "hibernate", "deadlock", "constraint",
                "connection pool", "hikari", "query", "transaction", "postgres", "oracle"));
        RULES.put("RESOURCE", List.of("outofmemory", "out of memory", "heap", "memory", "disk", "no space",
                "too many open files", "pool usage", "thread pool", "cpu"));
        RULES.put("NETWORK", List.of("timeout", "timed out", "connection refused", "unreachable", "unable to reach",
                "socket", "host", "dns", "503", "502", "504", "gateway"));
        RULES.put("CODE_DEFECT", List.of("nullpointer", "null pointer", "illegalstate", "illegalargument",
                "classcast", "indexoutofbounds", "arithmetic", "numberformat", "unsupportedoperation", "npe"));
        RULES.put("CONFIGURATION", List.of("config", "property", "properties", "bean", "environment",
                "missing", "not found", "no such", "classnotfound"));
        RULES.put("PERFORMANCE", List.of("slow", "latency", "took", "degraded", "retry"));
    }

    private AnomalyCategorizer() {
    }

    public static String categorize(String message, String stackTrace) {
        String text = ((message == null ? "" : message) + " " + (stackTrace == null ? "" : stackTrace))
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> rule : RULES.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (text.contains(keyword)) {
                    return rule.getKey();
                }
            }
        }
        return "OTHER";
    }
}
