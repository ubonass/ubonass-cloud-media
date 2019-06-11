package org.ubonass.media.server.kurento.core;

import lombok.Data;
import org.kurento.client.*;
import org.ubonass.media.server.core.SessionManager;
import org.ubonass.media.server.rpc.RpcConnection;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class KurentoCallSession {

    private MediaPipeline pipeline;
    /**
     * callee的sessionId,也就是ParticipantPrivateId
     */
    private String callingTo;
    /**
     * caller的sessionId，也就是ParticipantPrivateId
     */
    private String callingFrom;
    /**
     * key为session的privateId
     * value为WebRtcEndpoint
     */
    private Map<String, WebRtcEndpoint> webRtcEndpointMap = new ConcurrentHashMap<>();
    private Map<String, RtpEndpoint> rtpEndpointMap = new ConcurrentHashMap<>();

    public KurentoCallSession(KurentoClient kurento,
                              String callingFrom,
                              String callingTo) {
        try {
            this.callingFrom = callingFrom;
            this.callingTo = callingTo;
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
        WebRtcEndpoint oldEndpoint =
                webRtcEndpointMap.putIfAbsent(sessionId, endpoint);
        if (oldEndpoint != null)
            endpoint = oldEndpoint;
        return endpoint;
    }

    public WebRtcEndpoint getWebRtcEndpointById(String sessionId) {
        if (!webRtcEndpointMap.containsKey(sessionId)) return null;
        return webRtcEndpointMap.get(sessionId);
    }

    public RtpEndpoint createRtpEndPoint(String sessionId) {
        RtpEndpoint rtp = new RtpEndpoint.Builder(pipeline).build();
        RtpEndpoint oldRtp =
                rtpEndpointMap.putIfAbsent(sessionId, rtp);
        if (oldRtp != null) {
            rtp = oldRtp;
        }
        return rtp;
    }

    public RtpEndpoint getRtpEndpointById(String sessionId) {
        if (!rtpEndpointMap.containsKey(sessionId)) return null;
        return rtpEndpointMap.get(sessionId);
    }

    public void release() {
        /*Iterator<Map.Entry<String, WebRtcEndpoint>> it =
                webRtcEndpointMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WebRtcEndpoint> entry = it.next();
            it.remove();
        }*/
        if (webRtcEndpointMap != null) {
            webRtcEndpointMap.clear();
            webRtcEndpointMap = null;
        }
        if (rtpEndpointMap != null) {
            rtpEndpointMap.clear();
            rtpEndpointMap = null;
        }
        if (pipeline != null) {
            pipeline.release();
        }
    }

    public String getCallingTo() {
        return callingTo;
    }

    public String getCallingFrom() {
        return callingFrom;
    }

    @Data
    public static class RtpOfferProcessCallable
            implements Callable<String>, Serializable {
        private String clientId;
        private String offer;

        public RtpOfferProcessCallable(
                String clientId, String offer) {
            this.clientId = clientId;
            this.offer = offer;
        }

        @Override
        public String call() throws Exception {
            // ambito del otro pc
            if (clientId == null || offer == null) return null;
            //由client找到对应的KurentoCallSession
            SessionManager sessionManager = SessionManager.getContext();
            KurentoCallSession callSession =
                    sessionManager.getCallSession(clientId);
            if (callSession == null) return null;
            RtpEndpoint rtpEndpoint = callSession.getRtpEndpointById(clientId);
            if (rtpEndpoint == null) return null;
            return rtpEndpoint.processOffer(offer);
        }
    }
}
