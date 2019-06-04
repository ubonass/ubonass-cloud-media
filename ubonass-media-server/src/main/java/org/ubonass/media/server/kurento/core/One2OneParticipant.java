/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
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

package org.ubonass.media.server.kurento.core;

import org.kurento.client.IceCandidate;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class One2OneParticipant {

    private static final Logger log =
            LoggerFactory.getLogger(One2OneParticipant.class);
    private final String userId;
    private String sdpOffer;
    private WebRtcEndpoint webRtcEndpoint;
    private final List<IceCandidate> candidateList = new ArrayList<IceCandidate>();

    public One2OneParticipant(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSdpOffer() {
        return sdpOffer;
    }

    public void setSdpOffer(String sdpOffer) {
        this.sdpOffer = sdpOffer;
    }


    public void setWebRtcEndpoint(WebRtcEndpoint webRtcEndpoint) {
        this.webRtcEndpoint = webRtcEndpoint;

        for (IceCandidate e : candidateList) {
            this.webRtcEndpoint.addIceCandidate(e);
        }
        this.candidateList.clear();
    }

    public void addCandidate(IceCandidate candidate) {
        if (this.webRtcEndpoint != null) {
            this.webRtcEndpoint.addIceCandidate(candidate);
        } else {
            candidateList.add(candidate);
        }
    }

    public void clear() {
        this.webRtcEndpoint = null;
        this.candidateList.clear();
    }
}
