/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.diamond.zocalo;

import org.apache.activemq.broker.BrokerPluginSupport;
import org.apache.activemq.broker.ProducerBrokerExchange;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.policy.DeadLetterStrategy;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.Message;
import org.apache.activemq.filter.DestinationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Broker interceptor which updates the expiration timestamp on a message
 * depending on predefined rules.
 *
 */
public class ForceExpirationPlugin extends BrokerPluginSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ForceExpirationPlugin.class);
    
    private static final DestinationFilter zocaloQ = DestinationFilter.parseFilter(
    		new ActiveMQQueue("zocalo.transient.>"));
    private static final DestinationFilter zocaloT = DestinationFilter.parseFilter(
    		new ActiveMQTopic("zocalo.transient.>"));
    private static final DestinationFilter zocdevQ = DestinationFilter.parseFilter(
    		new ActiveMQQueue("zocdev.transient.>"));
    private static final DestinationFilter zocdevT = DestinationFilter.parseFilter(
    		new ActiveMQTopic("zocdev.transient.>"));
    		
    /**
    * variable which (when non-zero) is used to override
    * the expiration date for messages that arrive with
    * no expiration date set (in Milliseconds).
    */
    long zeroExpirationOverride = 1 * 60 * 60 * 1000; // 1 hr

    /**
    * variable which (when non-zero) is used to limit
    * the expiration date (in Milliseconds).
    */
    long ttlCeiling = 0;

    /**
     * If true, the plugin will not update timestamp to past values
     * False by default
     */
    boolean futureOnly = false;


    /**
     * if true, update timestamp even if message has passed through a network
     * default false
     */
    boolean processNetworkMessages = false;

    /**
    * setter method for zeroExpirationOverride
    */
    public void setZeroExpirationOverride(long ttl)
    {
        this.zeroExpirationOverride = ttl;
    }

    /**
    * setter method for ttlCeiling
    */
    public void setTtlCeiling(long ttlCeiling)
    {
        this.ttlCeiling = ttlCeiling;
    }

    public void setFutureOnly(boolean futureOnly) {
        this.futureOnly = futureOnly;
    }

    public void setProcessNetworkMessages(Boolean processNetworkMessages) {
        this.processNetworkMessages = processNetworkMessages;
    }

    @Override
    public void send(ProducerBrokerExchange producerExchange, Message message) throws Exception {
        if (message.getTimestamp() > 0 && message.getExpiration() <= 0 && !isDestinationDLQ(message) &&
           (processNetworkMessages || (message.getBrokerPath() == null || message.getBrokerPath().length == 0))) {
            // timestamp not been disabled and has not passed through a network or processNetworkMessages=true

            ActiveMQDestination destination = message.getDestination();
            if (destination != null) {
                if (zocaloQ.matches(destination) || zocaloT.matches(destination) ||
                    zocdevQ.matches(destination) || zocdevT.matches(destination)) {
                	String qname = destination.getQualifiedName();
//                  LOG.debug("FEP: Seen message {} in applicable destination {}", new Object[]{
//                        message.getMessageId(),
//                        qname
//                        });

                    long newTimeStamp = System.currentTimeMillis();
                    long timeToLive = zeroExpirationOverride;
//                  long oldTimestamp = message.getTimestamp();
                    message.setExpiration(newTimeStamp + timeToLive);
                    LOG.debug("Set message {} expiration to {}", new Object[]{ message.getMessageId(), message.getExpiration() });
                }
            }
        }
        super.send(producerExchange, message);
    }

    private boolean isDestinationDLQ(Message message) {
        DeadLetterStrategy deadLetterStrategy;
        Message tmp;

        Destination regionDestination = (Destination) message.getRegionDestination();
        if (message != null && regionDestination != null) {
            deadLetterStrategy = regionDestination.getDeadLetterStrategy();
            if (deadLetterStrategy != null && message.getOriginalDestination() != null) {
                // Cheap copy, since we only need two fields
                tmp = new ActiveMQMessage();
                tmp.setDestination(message.getOriginalDestination());
                tmp.setRegionDestination(regionDestination);

                // Determine if we are headed for a DLQ
                ActiveMQDestination deadLetterDestination = deadLetterStrategy.getDeadLetterQueueFor(tmp, null);
                if (deadLetterDestination.equals(message.getDestination())) {
                    return true;
                }
            }
        }
        return false;
    }
}
