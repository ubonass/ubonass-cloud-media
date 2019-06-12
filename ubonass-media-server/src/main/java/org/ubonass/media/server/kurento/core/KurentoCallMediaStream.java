package org.ubonass.media.server.kurento.core;

import org.kurento.client.*;
import org.ubonass.media.server.cluster.ClusterConnection;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class KurentoCallMediaStream {

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

    public KurentoCallMediaStream(KurentoClient kurento,
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

    public String getCallingTo() {
        return callingTo;
    }

    public String getCallingFrom() {
        return callingFrom;
    }

    public void release() {
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
            pipeline = null;
        }
    }

}
