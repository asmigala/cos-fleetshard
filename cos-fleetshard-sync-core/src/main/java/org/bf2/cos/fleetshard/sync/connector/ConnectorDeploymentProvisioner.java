package org.bf2.cos.fleetshard.sync.connector;

import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.bf2.cos.fleet.manager.model.ConnectorDeployment;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.OperatorSelector;
import org.bf2.cos.fleetshard.support.Constants;
import org.bf2.cos.fleetshard.support.ResourceUtil;
import org.bf2.cos.fleetshard.support.resources.Connectors;
import org.bf2.cos.fleetshard.support.resources.Secrets;
import org.bf2.cos.fleetshard.sync.client.FleetShardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bf2.cos.fleetshard.api.ManagedConnector.CONTEXT_DEPLOYMENT;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_CONTEXT;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_WATCH;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_DELETED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_STOPPED;

@ApplicationScoped
public class ConnectorDeploymentProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorDeploymentProvisioner.class);

    private final FleetShardClient fleetShard;

    public ConnectorDeploymentProvisioner(FleetShardClient connectorClient) {
        this.fleetShard = connectorClient;
    }

    public void provision(ConnectorDeployment deployment) {
        LOGGER.info("Got cluster_id: {}, connector_id: {}, deployment_id: {}, resource_version: {}",
            fleetShard.getClusterId(),
            deployment.getSpec().getConnectorId(),
            deployment.getId(),
            deployment.getMetadata().getResourceVersion());

        //
        // This is a little more convoluted than it should be because sensitive data
        // are transmitted as part of the payload so to properly set the ownership
        // of the secret which is required to ensure resources are cleaned up ween the
        // ManagedConnector resource is deleted we need to perform a two phase process
        // to create a ManagedConnector:
        //
        // 1. create the ManagedConnector
        // 2. create the Secret with the ManagedConnector as owner
        // 3. amend the ManagedConnector to include information about the Secret
        //
        // The operator has to wait till the information about the Secret to use are
        // properly set.
        //
        ManagedConnector base = createManagedConnector(deployment);

        //
        // When a connector has to be deleted, we don't set any secret as it is about
        // deleting a resources that is already existing and if needed the operator
        // can use the status information to retrieve any connector information related
        // to the state before being deleted.
        //
        switch (deployment.getSpec().getDesiredState()) {
            case STATE_DELETED:
            case STATE_STOPPED:
                LOGGER.info("Delete - managed_connector: {}/{})",
                    base.getMetadata().getNamespace(),
                    base.getMetadata().getName());
                break;
            default:
                Secret secret = createManagedConnectorSecret(deployment, base);
                ManagedConnector connector = fleetShard.editConnector(
                    base.getMetadata().getName(),
                    c -> {
                        c.getSpec().getDeployment().setSecret(secret.getMetadata().getName());
                        c.getSpec().getDeployment().setSecretChecksum(Secrets.computeChecksum(secret));
                    });

                LOGGER.info("CreateOrReplace - managed_connector: {}/{}, managed_connector_secret: {}/{}",
                    connector.getMetadata().getNamespace(), connector.getMetadata().getName(),
                    secret.getMetadata().getNamespace(), secret.getMetadata().getName());
        }
    }

    private ManagedConnector createManagedConnector(ConnectorDeployment deployment) {
        ManagedConnector connector = fleetShard.getConnector(deployment).orElseGet(() -> {
            LOGGER.info(
                "Connector not found (cluster_id: {}, connector_id: {}, deployment_id: {}, resource_version: {}), creating a new one",
                fleetShard.getClusterId(),
                deployment.getSpec().getConnectorId(),
                deployment.getId(),
                deployment.getMetadata().getResourceVersion());

            return Connectors.newConnector(
                ResourceUtil.generateConnectorId(),
                fleetShard.getClusterId(),
                deployment.getSpec().getConnectorId(),
                deployment.getId(),
                Map.of(LABEL_CONTEXT, CONTEXT_DEPLOYMENT));
        });

        // TODO: change APIs to include a single operator
        // move operator one level up
        // include full operator info in ConnectorDeployment APIs
        ArrayNode operatorsMeta = deployment.getSpec().getShardMetadata().withArray("operators");
        if (operatorsMeta.size() != 1) {
            throw new IllegalArgumentException("Multiple selectors are not yet supported");
        }

        OperatorSelector operatorSelector = new OperatorSelector(
            deployment.getSpec().getOperatorId(),
            operatorsMeta.get(0).requiredAt("/type").asText(),
            operatorsMeta.get(0).requiredAt("/version").asText());

        connector.getSpec().getDeployment().setDeploymentResourceVersion(deployment.getMetadata().getResourceVersion());
        connector.getSpec().getDeployment().setDesiredState(deployment.getSpec().getDesiredState());
        connector.getSpec().getDeployment().setConnectorTypeId(deployment.getSpec().getConnectorTypeId());
        connector.getSpec().getDeployment().setConnectorResourceVersion(deployment.getSpec().getConnectorResourceVersion());
        connector.getSpec().setOperatorSelector(operatorSelector);

        LOGGER.info("Provisioning connector id={} rv={} - {}/{}: {}",
            connector.getMetadata().getName(),
            connector.getSpec().getDeployment().getDeploymentResourceVersion(),
            fleetShard.getConnectorsNamespace(),
            connector.getSpec().getConnectorId(),
            Serialization.asJson(connector.getSpec()));

        return fleetShard.createConnector(connector);
    }

    private Secret createManagedConnectorSecret(ConnectorDeployment deployment, ManagedConnector owner) {
        Secret secret = fleetShard.getSecret(deployment)
            .orElseGet(() -> {
                LOGGER.info(
                    "Secret not found (cluster_id: {}, connector_id: {}, deployment_id: {}, resource_version: {}), creating a new one",
                    fleetShard.getClusterId(),
                    deployment.getSpec().getConnectorId(),
                    deployment.getId(),
                    deployment.getMetadata().getResourceVersion());

                return Secrets.newSecret(
                    owner.getMetadata().getName() + "-s-" + deployment.getMetadata().getResourceVersion(),
                    fleetShard.getClusterId(),
                    deployment.getSpec().getConnectorId(),
                    deployment.getId(),
                    deployment.getMetadata().getResourceVersion(),
                    Map.of(
                        LABEL_CONTEXT, CONTEXT_DEPLOYMENT,
                        LABEL_WATCH, "true"));
            });

        Secrets.set(secret, Constants.SECRET_ENTRY_CONNECTOR, deployment.getSpec().getConnectorSpec());
        Secrets.set(secret, Constants.SECRET_ENTRY_KAFKA, deployment.getSpec().getKafka());
        Secrets.set(secret, Constants.SECRET_ENTRY_META, deployment.getSpec().getShardMetadata());

        return fleetShard.createSecret(secret);
    }
}