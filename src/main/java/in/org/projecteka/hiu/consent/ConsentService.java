package in.org.projecteka.hiu.consent;

import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.Error;
import in.org.projecteka.hiu.ErrorRepresentation;
import in.org.projecteka.hiu.HiuProperties;
import in.org.projecteka.hiu.clients.GatewayServiceClient;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import in.org.projecteka.hiu.consent.model.*;
import in.org.projecteka.hiu.consent.model.consentmanager.ConsentRequest;
import in.org.projecteka.hiu.patient.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;

import static in.org.projecteka.hiu.ClientError.consentRequestNotFound;
import static in.org.projecteka.hiu.ErrorCode.INVALID_PURPOSE_OF_USE;
import static in.org.projecteka.hiu.common.Constants.EMPTY_STRING;
import static in.org.projecteka.hiu.common.Constants.STATUS;
import static in.org.projecteka.hiu.common.Constants.getCmSuffix;
import static in.org.projecteka.hiu.common.Serializer.to;
import static in.org.projecteka.hiu.consent.model.ConsentArtefactRepresentation.toConsentArtefactRepresentation;
import static in.org.projecteka.hiu.consent.model.ConsentRequestRepresentation.toConsentRequestRepresentation;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.DENIED;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.ERRORED;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.EXPIRED;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.GRANTED;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.REVOKED;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.POSTED;
import static java.time.LocalDateTime.now;
import static java.time.ZoneOffset.UTC;
import static java.util.UUID.fromString;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static reactor.core.publisher.Flux.fromIterable;
import static reactor.core.publisher.Mono.*;

public class ConsentService {
    private static final Logger logger = LoggerFactory.getLogger(ConsentService.class);
    private final HiuProperties hiuProperties;
    private final ConsentRepository consentRepository;
    private final DataFlowRequestPublisher dataFlowRequestPublisher;
    private final DataFlowDeletePublisher dataFlowDeletePublisher;
    private final PatientService patientService;
    private final HealthInformationPublisher healthInformationPublisher;
    private final ConceptValidator conceptValidator;
    private final GatewayServiceClient gatewayServiceClient;
    private final CacheAdapter<String, String> responseCache;
    private final Map<ConsentStatus, ConsentTask> consentTasks;
    private final PatientConsentRepository patientConsentRepository;
    private final CacheAdapter<String, String> patientRequestCache;
    private final ConsentServiceProperties consentServiceProperties;

    public ConsentService(HiuProperties hiuProperties,
                          ConsentRepository consentRepository,
                          DataFlowRequestPublisher dataFlowRequestPublisher,
                          DataFlowDeletePublisher dataFlowDeletePublisher,
                          PatientService patientService,
                          HealthInformationPublisher healthInformationPublisher,
                          ConceptValidator conceptValidator,
                          GatewayServiceClient gatewayServiceClient,
                          PatientConsentRepository patientConsentRepository,
                          ConsentServiceProperties consentServiceProperties,
                          CacheAdapter<String, String> patientRequestCache,
                          CacheAdapter<String, String> responseCache) {
        this.hiuProperties = hiuProperties;
        this.consentRepository = consentRepository;
        this.dataFlowRequestPublisher = dataFlowRequestPublisher;
        this.dataFlowDeletePublisher = dataFlowDeletePublisher;
        this.patientService = patientService;
        this.healthInformationPublisher = healthInformationPublisher;
        this.conceptValidator = conceptValidator;
        this.gatewayServiceClient = gatewayServiceClient;
        this.patientConsentRepository = patientConsentRepository;
        this.consentServiceProperties = consentServiceProperties;
        consentTasks = new HashMap<>();
        this.patientRequestCache = patientRequestCache;
        this.responseCache = responseCache;
    }

    private Mono<Void> validateConsentRequest(ConsentRequestData consentRequestData) {
        return conceptValidator.validatePurpose(consentRequestData.getConsent().getPurpose().getCode())
                .filter(result -> result)
                .switchIfEmpty(Mono.error(new ClientError(INTERNAL_SERVER_ERROR,
                        new ErrorRepresentation(new Error(INVALID_PURPOSE_OF_USE,
                                "Invalid Purpose Of Use")))))
                .then();
    }

    public Mono<Void> createRequest(String requesterId, ConsentRequestData consentRequestData) {
        var gatewayRequestId = UUID.randomUUID();
        return validateConsentRequest(consentRequestData)
                .then(sendConsentRequestToGateway(requesterId, consentRequestData, gatewayRequestId));
    }

    private Mono<Void> sendConsentRequestToGateway(
            String requesterId,
            ConsentRequestData hiRequest,
            UUID gatewayRequestId) {
        var reqInfo = hiRequest.getConsent().to(requesterId, hiuProperties.getId(), conceptValidator);
        var patientId = hiRequest.getConsent().getPatient().getId();
        var consentRequest = ConsentRequest.builder()
                .requestId(gatewayRequestId)
                .timestamp(now(UTC))
                .consent(reqInfo)
                .build();
        var hiuConsentRequest = hiRequest.getConsent().toConsentRequest(gatewayRequestId.toString(), requesterId);
        return consentRepository.insertConsentRequestToGateway(hiuConsentRequest)
                .then(gatewayServiceClient.sendConsentRequest(getCmSuffix(patientId), consentRequest));
    }

    public Mono<Void> updatePostedRequest(ConsentRequestInitResponse response) {
        var requestId = response.getResp().getRequestId();
        if (response.getError() != null) {
            logger.error("[ConsentService] Received error response from consent-request. HIU " +
                            "RequestId={}, Error code = {}, message={}",
                    requestId,
                    response.getError().getCode(),
                    response.getError().getMessage());
            return consentRepository.updateConsentRequestStatus(requestId, ERRORED, EMPTY_STRING);
        }

        if (response.getConsentRequest() != null) {
            var updatePublisher = consentRepository.consentRequestStatus(requestId)
                    .switchIfEmpty(error(consentRequestNotFound()))
                    .flatMap(status -> updateConsentRequestStatus(response, status));
            return patientRequestCache.get(requestId)
                    .switchIfEmpty(error(new NoSuchFieldError()))
                    .map(UUID::fromString)
                    .flatMap(dataRequestId -> {
                        var consentRequestId = fromString(response.getConsentRequest().getId());
                        return updatePublisher
                                .then(patientConsentRepository.updatePatientConsentRequest(dataRequestId,
                                        consentRequestId,
                                        now(UTC)));
                    })
                    .onErrorResume(NoSuchFieldError.class, e -> updatePublisher);
        }

        return error(ClientError.invalidDataFromGateway());
    }

    private Mono<Void> updateConsentRequestStatus(ConsentRequestInitResponse consentRequestInitResponse,
                                                  ConsentStatus oldStatus) {
        if (oldStatus.equals(POSTED)) {
            return consentRepository.updateConsentRequestStatus(
                    consentRequestInitResponse.getResp().getRequestId(),
                    ConsentStatus.REQUESTED,
                    consentRequestInitResponse.getConsentRequest().getId());
        }
        return empty();
    }

    public Flux<ConsentRequestRepresentation> requestsOf(String requesterId) {
        return consentRepository.requestsOf(requesterId)
                .take(consentServiceProperties.getDefaultPageSize())
                .collectList()
                .flatMapMany(list -> {
                    // Warming up cache
                    Set<String> patients = new java.util.HashSet<>(Set.of());
                    for (var result : list) {
                        var consentRequest =
                                (in.org.projecteka.hiu.consent.model.ConsentRequest) result.get("consentRequest");
                        patients.add(consentRequest.getPatient().getId());
                    }
                    return fromIterable(patients).flatMap(patientService::tryFind).thenMany(fromIterable(list));
                })
                .flatMap(result -> {
                    var consentRequest =
                            (in.org.projecteka.hiu.consent.model.ConsentRequest) result.get("consentRequest");
                    var consentRequestId = (String) result.get("consentRequestId");
                    consentRequestId = consentRequestId == null ? EMPTY_STRING : consentRequestId;
                    var status = (ConsentStatus) result.get(STATUS);
                    LocalDateTime dateModified = (LocalDateTime) result.get("dateModified");
                    dateModified = dateModified != null ? dateModified : consentRequest.getCreatedDate();
                    Mono<List<ConsentArtefactRepresentation>> consentArtefactRepresentations = empty();
                    if (status == GRANTED) {
                        consentArtefactRepresentations = getConsentArtefacts(consentRequestId).collectList();
                    }
                    return Mono.zip(patientService.tryFind(consentRequest.getPatient().getId()),
                            updateConsentStatusBasedOnArtefacts(consentRequest, status, consentArtefactRepresentations),
                            just(consentRequestId),
                            consentArtefactRepresentations.defaultIfEmpty(Collections.emptyList()),
                            just(dateModified));
                })
                .map(patientConsentRequest -> toConsentRequestRepresentation(patientConsentRequest.getT1(),
                        patientConsentRequest.getT2(),
                        patientConsentRequest.getT3(), patientConsentRequest.getT4(), patientConsentRequest.getT5()));
    }

    private Mono<in.org.projecteka.hiu.consent.model.ConsentRequest> updateConsentStatusBasedOnArtefacts(
            in.org.projecteka.hiu.consent.model.ConsentRequest consentRequest,
            ConsentStatus reqStatus,
            Mono<List<ConsentArtefactRepresentation>> consentArtefacts) {
        var consent = consentRequest.toBuilder().status(reqStatus).build();
        if (reqStatus.equals(GRANTED)) {
            return consentArtefacts
                    .map(consentArtefactDetails -> {
                        if (!consentArtefactDetails.isEmpty()) {
                            boolean isAnyGranted = consentArtefactDetails.stream()
                                    .anyMatch(artefact -> artefact.getStatus() == ConsentStatus.GRANTED);
                            Permission updatedPermission = consentRequest.getPermission();
                            updatedPermission.setDataEraseAt(consentArtefactDetails.get(0).getPermission().getDataEraseAt());
                            return consentRequest.toBuilder()
                                    .status(isAnyGranted ? ConsentStatus.GRANTED : consentArtefactDetails.get(0).getStatus())
                                    .permission(updatedPermission)
                                    .build();
                        }
                        return consentRequest;
                    })
                    .switchIfEmpty(Mono.just(consentRequest.toBuilder().status(reqStatus).build()));
        }
        return just(consent);
    }

    private Flux<ConsentArtefactRepresentation> getConsentArtefacts(String consentRequestId) {
        return consentRepository.getConsentDetails(consentRequestId)
                .map(consentArtefactDetail -> {
                    LocalDateTime consentArtefactExpiry = LocalDateTime.parse(consentArtefactDetail.get("consentExpiryDate"));
                    ConsentStatus consentArtefactStatus = consentArtefactExpiry.isBefore(now(UTC))
                            ? EXPIRED
                            : ConsentStatus.valueOf(consentArtefactDetail.get(STATUS));
                    return toConsentArtefactRepresentation(
                            to(consentArtefactDetail.get("consentArtefact"), ConsentArtefact.class),
                            consentArtefactStatus,
                            LocalDateTime.parse(consentArtefactDetail.get("dateModified"))
                    );
                })
                .switchIfEmpty(empty());
    }

    public Mono<Void> handleNotification(HiuConsentNotificationRequest hiuNotification) {
        return processConsentNotification(hiuNotification.getNotification(), hiuNotification.getTimestamp(), hiuNotification.getRequestId());
    }

    public Mono<Void> handleConsentArtefact(GatewayConsentArtefactResponse consentArtefactResponse) {
        if (consentArtefactResponse.getError() != null) {
            logger.error("[ConsentService] Received error response for consent-artefact. HIU " +
                            "RequestId={}, Error code = {}, message={}",
                    consentArtefactResponse.getResp().getRequestId(),
                    consentArtefactResponse.getError().getCode(),
                    consentArtefactResponse.getError().getMessage());
            return empty();
        }
        if (consentArtefactResponse.getConsent() != null) {
            return responseCache.get(consentArtefactResponse.getResp().getRequestId())
                    .flatMap(requestId -> consentRepository.insertConsentArtefact(
                            consentArtefactResponse.getConsent().getConsentDetail(),
                            consentArtefactResponse.getConsent().getStatus(),
                            requestId))
                    .then((defer(() -> dataFlowRequestPublisher.broadcastDataFlowRequest(
                            consentArtefactResponse.getConsent().getConsentDetail().getConsentId(),
                            consentArtefactResponse.getConsent().getConsentDetail().getPermission().getDateRange(),
                            consentArtefactResponse.getConsent().getSignature(),
                            hiuProperties.getDataPushUrl()))));
        }
        logger.error("Unusual response = {} from CM", consentArtefactResponse);
        return empty();
    }

    public Mono<Void> handleConsentRequestStatus(ConsentStatusRequest consentStatusRequest) {
        if (consentStatusRequest.getError() != null) {
            logger.error("[ConsentService] Received error response for consent-status. HIU " +
                            "RequestId={}, Error code = {}, message={}",
                    consentStatusRequest.getResp().getRequestId(),
                    consentStatusRequest.getError().getCode(),
                    consentStatusRequest.getError().getMessage());
            return empty();
        }
        if (consentStatusRequest.getConsentRequest() != null) {
            logger.info("[ConsentService] Received consent request response for consent-status: {}" +
                    consentStatusRequest.getConsentRequest());
            return consentRepository
                    .getConsentRequestStatus(consentStatusRequest.getConsentRequest().getId())
                    .switchIfEmpty(Mono.error(ClientError.consentRequestNotFound()))
                    .filter(consentStatus -> consentStatus != consentStatusRequest.getConsentRequest().getStatus())
                    .flatMap(consentRequest -> consentRepository
                            .updateConsentRequestStatus(consentStatusRequest.getConsentRequest().getStatus(),
                                    consentStatusRequest.getConsentRequest().getId()));
        }
        logger.error("Unusual response = {} from CM", consentStatusRequest);
        return empty();
    }

    private Mono<Void> processConsentNotification(ConsentNotification notification, LocalDateTime localDateTime, UUID requestId) {
        var consentTask = consentTasks.get(notification.getStatus());
        if (consentTask == null) {
            return error(ClientError.validationFailed());
        }
        return consentTask.perform(notification, localDateTime, requestId);
    }

    @PostConstruct
    private void postConstruct() {
        consentTasks.put(GRANTED, new GrantedConsentTask(consentRepository, gatewayServiceClient, responseCache));
        consentTasks.put(REVOKED, new RevokedConsentTask(consentRepository, healthInformationPublisher, gatewayServiceClient));
        consentTasks.put(EXPIRED, new ExpiredConsentTask(consentRepository, dataFlowDeletePublisher, gatewayServiceClient));
        consentTasks.put(DENIED, new DeniedConsentTask(consentRepository));
    }
}
