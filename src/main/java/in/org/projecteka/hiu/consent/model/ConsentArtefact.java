package in.org.projecteka.hiu.consent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import in.org.projecteka.hiu.consent.model.consentmanager.HIU;
import in.org.projecteka.hiu.consent.model.consentmanager.Permission;
import in.org.projecteka.hiu.consent.model.consentmanager.Purpose;
import in.org.projecteka.hiu.consent.model.consentmanager.Requester;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties
public class ConsentArtefact {
    private String consentId;
    private Date createdAt;
    private Purpose purpose;
    private PatientLinkedContext patient;
    private HIPReference hip;
    private HIU hiu;
    private Requester requester;
    private HIType[] hiTypes;
    private Permission permission;
}