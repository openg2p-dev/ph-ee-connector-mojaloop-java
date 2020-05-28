package org.mifos.connector.mojaloop.transactionrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.AUTH_RETRIES_LEFT_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PAYER_CONFIRMATION_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSACTION_REQUEST_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSACTION_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_SEND_AUTH_CONFIRMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_SEND_AUTH_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_SEND_TRANSACTION_STATE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_TRANSACTION_REQUEST;

@Component
public class TransactionWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating " + WORKER_TRANSACTION_REQUEST + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_TRANSACTION_REQUEST + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();
                        existingVariables.put(TRANSACTION_REQUEST_RETRY_COUNT, 1 + (Integer) existingVariables.getOrDefault(TRANSACTION_REQUEST_RETRY_COUNT, -1));

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                        exchange.setProperty(PARTY_LOOKUP_FSP_ID, existingVariables.get(PARTY_LOOKUP_FSP_ID));
                        producerTemplate.send("direct:send-transaction-request", exchange);

                        client.newCompleteCommand(job.getKey())
                                .variables(existingVariables)
                                .send()
                                .join();
                    })
                    .name(WORKER_TRANSACTION_REQUEST + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_SEND_AUTH_CONFIRMATION + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_SEND_AUTH_CONFIRMATION + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

//                        Exchange exchange = new DefaultExchange(camelContext);
//                        exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
//
//                        producerTemplate.send("", exchange);

                        client.newCompleteCommand(job.getKey())
                                .send()
                                .join();
                    })
                    .name(WORKER_SEND_AUTH_CONFIRMATION + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_SEND_AUTH_RESPONSE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_SEND_AUTH_RESPONSE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();
                        existingVariables.put(PAYER_CONFIRMATION_RETRY_COUNT, 1 + (Integer) existingVariables.getOrDefault(PAYER_CONFIRMATION_RETRY_COUNT, -1));
                        existingVariables.put(AUTH_RETRIES_LEFT_COUNT, 1 + (Integer) existingVariables.getOrDefault(AUTH_RETRIES_LEFT_COUNT, -1));

//                        Exchange exchange = new DefaultExchange(camelContext);
//                        exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
//                        producerTemplate.send("", exchange);

                        client.newCompleteCommand(job.getKey())
                                .variables(existingVariables)
                                .send()
                                .join();
                    })
                    .name(WORKER_SEND_AUTH_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_SEND_TRANSACTION_STATE_RESPONSE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_SEND_TRANSACTION_STATE_RESPONSE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();
                        TransactionChannelRequestDTO channelRequest = objectMapper.readValue((String) existingVariables.get(CHANNEL_REQUEST), TransactionChannelRequestDTO.class);

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                        exchange.setProperty(TRANSACTION_STATE, existingVariables.get(TRANSACTION_STATE));
                        exchange.setProperty(FSPIOP_SOURCE.headerName(), channelRequest.getPayer().getPartyIdInfo().getFspId());
                        exchange.setProperty(FSPIOP_DESTINATION.headerName(), channelRequest.getPayee().getPartyIdInfo().getFspId());
                        producerTemplate.send("direct:send-transaction-state", exchange);

                        client.newCompleteCommand(job.getKey())
                                .send()
                                .join();
                    })
                    .name(WORKER_SEND_TRANSACTION_STATE_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();


        }
    }


}