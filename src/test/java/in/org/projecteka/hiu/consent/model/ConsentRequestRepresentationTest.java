package in.org.projecteka.hiu.consent.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static in.org.projecteka.hiu.consent.TestBuilders.consentRequest;
import static in.org.projecteka.hiu.consent.TestBuilders.patient;
import static in.org.projecteka.hiu.consent.model.ConsentRequestRepresentation.toConsentRequestRepresentation;
import static org.assertj.core.api.Assertions.assertThat;

class ConsentRequestRepresentationTest {

    @Test
    void returnConsentRequestRepresentation() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'HH[:mm][:ss][.SSS]]");
        var expiryAt = LocalDateTime.parse("2200-06-02T10:15:02.325", formatter);
        var todayAt = LocalDateTime.parse("2020-06-02T10:15:02", formatter);
        var modifiedAt = LocalDateTime.parse("2020-06-02T11:15:02", formatter);
        var consentRequest = consentRequest()
                .permission(Permission.builder().dataEraseAt(expiryAt).build())
                .status(ConsentStatus.GRANTED)
                .createdDate(todayAt).build();
        var patient = patient().identifier(consentRequest.getPatient().getId()).build();
        var consentArtefact = new ConsentArtefactRepresentation("","Bahmni",ConsentStatus.GRANTED,todayAt,todayAt, in.org.projecteka.hiu.consent.model.consentmanager.Permission.builder().dataEraseAt(expiryAt).build(), new HIType[]{HIType.PRESCRIPTION});
        var expected = new ConsentRequestRepresentation(
                consentRequest.getId(),
                consentRequest.getId(),
                new PatientRepresentation(patient.getIdentifier(), patient.getFirstName(), patient.getLastName()),
                consentRequest.getStatus(),
                expiryAt,
                todayAt,
                modifiedAt,
                modifiedAt,
                consentRequest.getPermission(),
                consentRequest.getHiTypes(),
                List.of(consentArtefact));

        var consentRequestRepresentation = toConsentRequestRepresentation(patient, consentRequest,consentRequest.getId(),List.of(consentArtefact),modifiedAt);

        assertThat(consentRequestRepresentation).isEqualTo(expected);
    }

    @Test
    void returnApprovedDateAsNullWhenStatusIsDenied() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'HH[:mm][:ss][.SSS]]");
        var expiryAt = LocalDateTime.parse("2200-06-02T10:15:02.325", formatter);
        var todayAt = LocalDateTime.parse("2020-06-02T10:15:02", formatter);
        var modifiedAt = LocalDateTime.parse("2020-06-02T11:15:02", formatter);
        var consentRequest = consentRequest()
                .permission(Permission.builder().dataEraseAt(expiryAt).build())
                .status(ConsentStatus.DENIED)
                .createdDate(todayAt).build();
        var patient = patient().identifier(consentRequest.getPatient().getId()).build();
        var expected = new ConsentRequestRepresentation(
                consentRequest.getId(),
                consentRequest.getId(),
                new PatientRepresentation(patient.getIdentifier(), patient.getFirstName(), patient.getLastName()),
                consentRequest.getStatus(),
                expiryAt,
                todayAt,
                null,
                modifiedAt,
                consentRequest.getPermission(),
                consentRequest.getHiTypes(),
                Collections.emptyList());

        var consentRequestRepresentation = toConsentRequestRepresentation(patient, consentRequest,consentRequest.getId(),Collections.emptyList(),modifiedAt);

        assertThat(consentRequestRepresentation).isEqualTo(expected);
    }
}
