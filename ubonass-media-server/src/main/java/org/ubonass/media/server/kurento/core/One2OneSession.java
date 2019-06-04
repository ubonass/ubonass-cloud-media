package org.ubonass.media.server.kurento.core;

import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;

public class One2OneSession {
    private MediaPipeline pipeline;
    private WebRtcEndpoint callerWebRtcEp;
    private WebRtcEndpoint calleeWebRtcEp;
    private String sessionId;

    public One2OneSession(String sessionId, KurentoClient kurento) {
        this.sessionId = sessionId;
        try {
            this.pipeline = kurento.createMediaPipeline();
            this.callerWebRtcEp = new WebRtcEndpoint.Builder(pipeline).build();
            this.calleeWebRtcEp = new WebRtcEndpoint.Builder(pipeline).build();

            this.callerWebRtcEp.connect(this.calleeWebRtcEp);

            this.calleeWebRtcEp.connect(this.callerWebRtcEp);
        } catch (Throwable t) {
            if (this.pipeline != null) {
                pipeline.release();
            }
        }
    }

    public String generateSdpAnswerForCaller(String sdpOffer) {
        return callerWebRtcEp.processOffer(sdpOffer);
    }

    public String generateSdpAnswerForCallee(String sdpOffer) {
        return calleeWebRtcEp.processOffer(sdpOffer);
    }

    public void release() {
        if (pipeline != null) {
            pipeline.release();
        }
    }

    public WebRtcEndpoint getCallerWebRtcEp() {
        return callerWebRtcEp;
    }

    public WebRtcEndpoint getCalleeWebRtcEp() {
        return calleeWebRtcEp;
    }
}
