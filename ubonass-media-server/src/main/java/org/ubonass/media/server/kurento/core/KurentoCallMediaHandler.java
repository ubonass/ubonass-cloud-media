package org.ubonass.media.server.kurento.core;

import lombok.Data;
import org.kurento.client.RtpEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.core.SessionManager;

import java.io.Serializable;
import java.util.concurrent.Callable;

@Data
public class KurentoCallMediaHandler
        implements Callable<String>, Runnable, Serializable {
    private static final Logger logger = LoggerFactory.getLogger(KurentoCallMediaHandler.class);

    public static final String MEDIA_EVENT_PROCESS_SDPOFFER = "rtpProcessOffer";
    public static final String MEDIA_EVENT_RELEASE_STREAM = "releaseMediaStream";

    private String clientId;
    private String sdp;
    private String event;

    public KurentoCallMediaHandler(
            String clientId, String sdp, String event) {
        this.clientId = clientId;
        this.sdp = sdp;
        this.event = event;
    }

    public KurentoCallMediaHandler(
            String clientId,String event) {
        this.clientId = clientId;
        this.event = event;
    }

    @Override
    public String call() throws Exception {
        String result = null;
        if (event == null) return null;
        switch (event) {
            case MEDIA_EVENT_PROCESS_SDPOFFER:
                KurentoCallMediaStream mediaStream =
                        SessionManager.getContext().getCallSession(clientId);
                RtpEndpoint rtpEndpoint =
                        mediaStream.getRtpEndpointById(clientId);
                result = rtpEndpoint.processOffer(sdp);
                break;
            default:
                break;
        }
        return result;
    }

    @Override
    public void run() {
        if (event == null) return;
        switch (event) {
            case MEDIA_EVENT_RELEASE_STREAM:
                KurentoCallMediaStream mediaStream =
                        SessionManager.getContext().removeCallSession(clientId);
                if (mediaStream != null) {
                    mediaStream.release();
                    mediaStream = null;
                }
                break;
            default:
                break;
        }
    }
}
