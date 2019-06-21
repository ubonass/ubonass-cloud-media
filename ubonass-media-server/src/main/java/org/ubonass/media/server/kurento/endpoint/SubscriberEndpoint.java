/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.ubonass.media.server.kurento.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.kurento.client.MediaPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.kurento.core.KurentoParticipant;

import java.util.Map.Entry;

/**
 * Subscriber aspect of the {@link MediaEndpoint}.
 *
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class SubscriberEndpoint extends MediaEndpoint {
    private final static Logger log = LoggerFactory.getLogger(SubscriberEndpoint.class);

    private boolean connectedToPublisher = false;

    //如果是本host则对应一个PublisherEndpoint
    private PublisherEndpoint publisher = null;

    /**
     *
     * @param web
     * @param owner
     * @param endpointName:对应哪个publisher的endpointName
     * @param pipeline
     * @param cloudMediaConfig
     */
    public SubscriberEndpoint(boolean web,
                              KurentoParticipant owner,
                              String endpointName,
                              MediaPipeline pipeline,
                              CloudMediaConfig cloudMediaConfig) {
        super(web, owner, endpointName, pipeline, cloudMediaConfig, log);
        //如果endpointName对应的publisher不在本host上这里应该要创建一个rtpEndpoint
    }

    /**
     * @param sdpOffer :当前endpoint发送来的sdpOffer
     * @param publisher:这是要接收哪个流？
     * @return
     */
    public synchronized String subscribe(String sdpOffer, PublisherEndpoint publisher) {
        //为啥？
        registerOnIceCandidateEventListener(publisher.getOwner().getParticipantPublicId());
        String sdpAnswer = processOffer(sdpOffer);
        gatherCandidates();
        publisher.connect(this.getEndpoint());
        setConnectedToPublisher(true);
        setPublisher(publisher);
        this.createdAt = System.currentTimeMillis();
        return sdpAnswer;
    }

    public boolean isConnectedToPublisher() {
        return connectedToPublisher;
    }

    public void setConnectedToPublisher(boolean connectedToPublisher) {
        this.connectedToPublisher = connectedToPublisher;
    }

    /*@Override
    public PublisherEndpoint getPublisher() {
        return this.publisher;
    }*/

    public void setPublisher(PublisherEndpoint publisher) {
        this.publisher = publisher;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = super.toJson();
        try {
            json.addProperty("streamId", this.publisher.getStreamId());
        } catch (NullPointerException ex) {
            json.addProperty("streamId", "NOT_FOUND");
        }
        return json;
    }

    @Override
    public JsonObject withStatsToJson() {
        JsonObject json = super.withStatsToJson();
        JsonObject toJson = this.toJson();
        for (Entry<String, JsonElement> entry : toJson.entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        return json;
    }
}
