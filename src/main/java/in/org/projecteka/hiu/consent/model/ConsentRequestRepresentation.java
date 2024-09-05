package in.org.projecteka.hiu.consent.model;

import in.org.projecteka.hiu.clients.Patient;
import lombok.SneakyThrows;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
public class ConsentRequestRepresentation {
    String id;
    String consentRequestId;
    PatientRepresentation patient;
    ConsentStatus status;
    LocalDateTime expiredDate;
    LocalDateTime createdDate;
    LocalDateTime approvedDate;
    LocalDateTime dateModified;
    Permission permission;
    List<HIType> hiTypes;
    List<ConsentArtefactRepresentation> consentArtefacts;

    @SneakyThrows
    public static ConsentRequestRepresentation toConsentRequestRepresentation(
            Patient patient,
            in.org.projecteka.hiu.consent.model.ConsentRequest consentRequest, String consentRequestId, List<ConsentArtefactRepresentation> consentArtefacts, LocalDateTime dateModified) {
        LocalDateTime approvedDate = consentRequest.getStatus().equals(ConsentStatus.GRANTED) ||
                consentRequest.getStatus().equals(ConsentStatus.EXPIRED) ||
                consentRequest.getStatus().equals(ConsentStatus.REVOKED) ? dateModified: null;
        return new ConsentRequestRepresentation(
                consentRequest.getId(),
                consentRequestId,
                new PatientRepresentation(
                        patient.getIdentifier(),
                        patient.getFirstName(),
                        patient.getLastName()),
                consentRequest.getStatus(),
                consentRequest.getPermission().getDataEraseAt(),
                consentRequest.getCreatedDate(),
                approvedDate,
                dateModified,
                consentRequest.getPermission(),
                consentRequest.getHiTypes(),
                consentArtefacts);
    }
}

