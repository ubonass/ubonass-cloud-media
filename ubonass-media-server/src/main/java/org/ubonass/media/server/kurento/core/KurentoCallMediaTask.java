package org.ubonass.media.server.kurento.core;

import lombok.Data;
import org.kurento.client.RtpEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.core.SessionManager;

import java.io.Serializable;
import java.util.concurrent.Callable;

@Data
public class KurentoCallMediaTask
        implements Callable<String>, Serializable {
    private static final Logger logger = LoggerFactory.getLogger(KurentoCallMediaTask.class);

    private String clientId;
    private String sdp;
    private String event;//rtpProcessOffer,releaseMediaStream

    public KurentoCallMediaTask(
            String clientId, String sdp, String event) {
        this.clientId = clientId;
        this.sdp = sdp;
        this.event = event;
    }

    @Override
    public String call() throws Exception {
        if (event.equals("rtpProcessOffer")) {
            logger.info("do rtpProcessOffer.....");
            //由client找到对应的KurentoCallSession
            KurentoCallMediaStream callStream =
                    SessionManager.getContext().getCallSession(clientId);
            if (callStream == null) return null;
            RtpEndpoint rtpEndpoint = callStream.getRtpEndpointById(clientId);
            if (rtpEndpoint == null) return null;
            logger.info("rtpEndpoint processOffer success..");
            String sdpAnswer = rtpEndpoint.processOffer(sdp);
            return sdpAnswer;
        } else if (event.equals("releaseMediaStream")) {
            logger.info("do releaseMediaStream.....");
            KurentoCallMediaStream session =
                    SessionManager.getContext().removeCallSession(clientId);
            if (session != null)
                session.release();
            session = null;
            return null;
        }
        return null;
    }
}
