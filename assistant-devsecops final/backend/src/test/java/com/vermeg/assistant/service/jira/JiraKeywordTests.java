/**
 * Role : verifier l'extraction de mots-cles et l'empreinte utilisees pour rapprocher anomalies et tickets Jira.
 * Contenu : tests unitaires des fonctions utilitaires de JiraService.
 */

package com.vermeg.assistant.service.jira;

import com.vermeg.assistant.model.JiraModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraKeywordTests {

    @Test
    void keywordsPutErrorCodesAndExceptionsFirst() {
        List<String> k = JiraService.keywords("IJ000453: Unable to get managed connection for java:jboss/datasources/TitresDS "
                + "Caused by: java.net.ConnectException: Connection refused #");
        assertThat(k.get(0)).isEqualTo("IJ000453");
        assertThat(k).contains("ConnectException");
        assertThat(k).noneMatch(w -> w.contains("#"));
        assertThat(k.size()).isLessThanOrEqualTo(6);
    }

    @Test
    void fingerprintIsStableAndBasedOnTheIncidentReference() {
        String a = JiraService.fingerprint(new JiraModels.IncidentQuery("app", "log_anomaly", "anomaly-1234567890abcdef", "texte A", null));
        String b = JiraService.fingerprint(new JiraModels.IncidentQuery("app", "log_anomaly", "anomaly-1234567890abcdef", "texte B", null));
        String c = JiraService.fingerprint(new JiraModels.IncidentQuery("app", "log_anomaly", "anomaly-ffffffffffffffff", "texte A", null));
        assertThat(a).isEqualTo(b).startsWith("devsecops-");
        assertThat(a).isNotEqualTo(c);
        assertThat(a.length()).isEqualTo("devsecops-".length() + 12);
    }
}
