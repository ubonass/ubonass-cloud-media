package org.ubonass.media.server.kurento.core;

import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KurentoCallMediaStream {
    private static final Logger logger = LoggerFactory.getLogger(KurentoCallMediaStream.class);
    private MediaPipeline pipeline;
    /**
     * callee的ClientId
     */
    private String callingTo;
    /**
     * caller的sessionId，也就是ClientId
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

    public WebRtcEndpoint createWebRtcEndpoint(String clientId) {
        WebRtcEndpoint endpoint =
                new WebRtcEndpoint.Builder(pipeline).build();
        WebRtcEndpoint oldEndpoint =
                webRtcEndpointMap.putIfAbsent(clientId, endpoint);
        if (oldEndpoint != null)
            endpoint = oldEndpoint;
        return endpoint;
    }

    public WebRtcEndpoint getWebRtcEndpointById(String clientId) {
        if (!webRtcEndpointMap.containsKey(clientId)) return null;
        return webRtcEndpointMap.get(clientId);
    }

    public RtpEndpoint createRtpEndPoint(String clientId) {
        RtpEndpoint rtp = new RtpEndpoint.Builder(pipeline).build();
        RtpEndpoint oldRtp =
                rtpEndpointMap.putIfAbsent(clientId, rtp);
        if (oldRtp != null) {
            rtp = oldRtp;
        }
        return rtp;
    }

    public RtpEndpoint getRtpEndpointById(String clientId) {
        if (!rtpEndpointMap.containsKey(clientId)) return null;
        return rtpEndpointMap.get(clientId);
    }

    public String getCallingTo() {
        return callingTo;
    }

    public String getCallingFrom() {
        return callingFrom;
    }

    public void release() {
        logger.debug("enter release....");
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
