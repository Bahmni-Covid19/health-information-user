package in.org.projecteka.hiu.consent.model;

import in.org.projecteka.hiu.consent.model.consentmanager.Permission;
import lombok.SneakyThrows;
import lombok.Value;

import java.time.LocalDateTime;

@Value
public class ConsentArtefactRepresentation {
    String consentArtefactId;
    String hipName;
    ConsentStatus status;
    LocalDateTime createdDate;
    LocalDateTime dateModified;
    Permission permission;
    HIType[] hiTypes;

    @SneakyThrows
    public static ConsentArtefactRepresentation toConsentArtefactRepresentation(
            ConsentArtefact consentArtefact, ConsentStatus consentStatus, LocalDateTime dateModified) {
        return new ConsentArtefactRepresentation(
                consentArtefact.getConsentId(),
                consentArtefact.getHip().getName(),
                consentStatus,
                consentArtefact.getCreatedAt(),
                dateModified,
                consentArtefact.getPermission(),
                consentArtefact.getHiTypes()
        );
    }
}

