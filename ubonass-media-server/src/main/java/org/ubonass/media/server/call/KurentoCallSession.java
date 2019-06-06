package org.ubonass.media.server.call;

import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KurentoCallSession {

    private MediaPipeline pipeline;
    /**
     * key为session的privateId
     * value为WebRtcEndpoint
     */
    private Map<String, WebRtcEndpoint> webRtcEndpointMap = new ConcurrentHashMap<>();

    public KurentoCallSession(KurentoClient kurento) {
        try {
            this.pipeline = kurento.createMediaPipeline();
        } catch (Throwable t) {
            if (this.pipeline != null) {
                pipeline.release();
            }
        }
    }

    public WebRtcEndpoint createWebRtcEndpoint(String sessionId) {
        WebRtcEndpoint endpoint =
                new WebRtcEndpoint.Builder(pipeline).build();
        WebRtcEndpoint old =
                webRtcEndpointMap.putIfAbsent(sessionId, endpoint);
        if (old != null) {
            endpoint.release();
            endpoint = null;
            return old;
        } else {
            return endpoint;
        }
    }

    public WebRtcEndpoint getWebRtcEndpointBySessionId(String sessionId) {
        if (!webRtcEndpointMap.containsKey(sessionId)) return null;
        return webRtcEndpointMap.get(sessionId);
    }

    public void release() {
        /*Iterator<Map.Entry<String, WebRtcEndpoint>> it =
                webRtcEndpointMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WebRtcEndpoint> entry = it.next();
            it.remove();
        }*/
        webRtcEndpointMap.clear();
        webRtcEndpointMap = null;
        if (pipeline != null) {
            pipeline.release();
        }
    }
}
