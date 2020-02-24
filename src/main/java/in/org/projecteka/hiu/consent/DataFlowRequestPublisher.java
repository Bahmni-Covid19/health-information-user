package in.org.projecteka.hiu.consent;

import in.org.projecteka.hiu.DestinationsConfig;
import in.org.projecteka.hiu.consent.model.dataflow.Consent;
import in.org.projecteka.hiu.consent.model.dataflow.DataFlowRequest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.log4j.Logger;
import org.springframework.amqp.core.AmqpTemplate;
import reactor.core.publisher.Mono;

import static in.org.projecteka.hiu.ClientError.queueNotFound;
import static in.org.projecteka.hiu.HiuConfiguration.DATA_FLOW_REQUEST_QUEUE;

@AllArgsConstructor
public class DataFlowRequestPublisher {
    private static final Logger logger = Logger.getLogger(DataFlowRequestPublisher.class);
    private AmqpTemplate amqpTemplate;
    private DestinationsConfig destinationsConfig;

    @SneakyThrows
    public Mono<Void> broadcastDataFlowRequest(String consentArtefactId, String signature, String callBackUrl) {
        DestinationsConfig.DestinationInfo destinationInfo =
                destinationsConfig.getQueues().get(DATA_FLOW_REQUEST_QUEUE);

        if (destinationInfo == null) {
            logger.info(DATA_FLOW_REQUEST_QUEUE + " not found");
            throw queueNotFound();
        }

        return Mono.create(monoSink -> {
            amqpTemplate.convertAndSend(
                    destinationInfo.getExchange(),
                    destinationInfo.getRoutingKey(),
                    DataFlowRequest.builder()
                            .consent(Consent.builder().
                                    id(consentArtefactId)
                                    .digitalSignature(signature)
                                    .build())
                            .callBackUrl(callBackUrl)
                            .build());
            logger.info("Broadcasting data flow request with consent id : " + consentArtefactId);
            monoSink.success();
        });
    }
}