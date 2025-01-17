Feature: Debezium Connector Reify

  Background:
    Given Await configuration
      | atMost       | 30000   |
      | pollDelay    | 100     |
      | pollInterval | 500     |

  Scenario: reify
    Given a Connector with:
      | connector.type.id           | debezium-postgres-1.9.0.Alpha2    |
      | desired.state               | ready                            |
      | kafka.bootstrap             | kafka.acme.com:443               |
      | operator.id                 | cos-fleetshard-operator-debezium |
      | operator.type               | debezium-connector-operator      |
      | operator.version            | [1.0.0,2.0.0)                    |
    And with a simple Debezium connector

    When deploy
    Then the connector exists
     And the connector secret exists
     And the connector is in phase "Monitor"

    Then the kc exists
     And the kc has labels containing:
       | cos.bf2.org/cluster.id    | ${cos.cluster.id}             |
       | cos.bf2.org/connector.id  | ${cos.connector.id}           |
       | cos.bf2.org/deployment.id | ${cos.deployment.id}          |

    And the kc has an entry at path "$.metadata.ownerReferences[0].apiVersion" with value "cos.bf2.org/v1alpha1"
    And the kc has an entry at path "$.metadata.ownerReferences[0].kind" with value "ManagedConnector"
    And the kc has an entry at path "$.spec.authentication.passwordSecret.secretName" with value "${cos.managed.connector.name}-config"
    And the kc has an entry at path "$.spec.authentication.passwordSecret.password" with value "_kafka.client.secret"
    And the kc has an entry at path "$.spec.image" with value "quay.io/rhoas/cos-connector-debezium-postgres@sha256:b67d0ef4d4638bd5b6e71e2ccc30d5f7d5f74738db94dae53504077de7df5cff"
    And the kc has config containing:
      | config.providers                  | file,dir                             |
      | config.storage.replication.factor | 3                                    |
      | config.storage.topic              | ${cos.managed.connector.name}-config |
      | offset.storage.topic              | ${cos.managed.connector.name}-offset |
      | status.storage.topic              | ${cos.managed.connector.name}-status |
      | group.id                          | ${cos.managed.connector.name}        |
      | connector.secret.name             | ${cos.managed.connector.name}-config |
      | connector.secret.checksum         | ${cos.ignore}                        |
      | key.converter                     | org.apache.kafka.connect.json.JsonConverter                                                                       |
      | value.converter                   | org.apache.kafka.connect.json.JsonConverter                                                                       |

    Then the kctr exists
    And the kctr has labels containing:
      | cos.bf2.org/cluster.id    | ${cos.cluster.id}             |
      | cos.bf2.org/connector.id  | ${cos.connector.id}           |
      | cos.bf2.org/deployment.id | ${cos.deployment.id}          |
      | strimzi.io/cluster        | ${cos.managed.connector.name} |

    And the kctr has an entry at path "$.metadata.ownerReferences[0].apiVersion" with value "cos.bf2.org/v1alpha1"
    And the kctr has an entry at path "$.metadata.ownerReferences[0].kind" with value "ManagedConnector"
    And the kctr has an entry at path "$.spec.pause" with value false
    And the kctr has an entry at path "$.spec.tasksMax" with value 1
    And the kctr has an entry at path "$.spec.class" with value "io.debezium.connector.postgresql.PostgresConnector"
    And the kctr has config containing:
      | database.password                 | ${file:/opt/kafka/external-configuration/connector-configuration/debezium-connector.properties:database.password} |



