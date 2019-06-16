package org.ubonass.media.server.kurento.endpoint;

import org.kurento.client.MediaElement;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.MediaOptions;
import org.ubonass.media.server.kurento.core.KurentoParticipant;

/**
 * for one to one call
 */
public class CallMediaStream extends MediaEndpoint {
    private final static Logger log = LoggerFactory.getLogger(CallMediaStream.class);

    protected MediaOptions mediaOptions;

    public CallMediaStream(boolean web,
                           KurentoParticipant owner,
                           String endpointName,
                           MediaPipeline pipeline,
                           CloudMediaConfig cloudMediaConfig) {
        super(web, owner, endpointName, pipeline, cloudMediaConfig, log);
    }


    @Override
    public PublisherEndpoint getPublisher() {
        return null;
    }

    private void checkCurrentEndpointValid() {
        if (this.getEndpoint() == null) {
            throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                    "Can't connect null endpoint (ep: " + getEndpointName() + ")");
        }
    }

    public synchronized void connect(MediaElement sink) {
        checkCurrentEndpointValid();
        if (sink != null) {
            internalSinkConnect(this.getEndpoint(), sink);
        }
    }

    public synchronized void connect(MediaElement sink, MediaType type) {
        checkCurrentEndpointValid();
        if (sink != null) {
            internalSinkConnect(this.getEndpoint(), sink,type);
        }
    }

    public synchronized void disconnect(MediaElement sink) {
        checkCurrentEndpointValid();
        if (sink != null) {
            internalSinkDisconnect(this.getEndpoint(), sink);
        }
    }

    public synchronized void disconnect(MediaElement sink, MediaType type) {
        checkCurrentEndpointValid();
        if (sink != null) {
            internalSinkDisconnect(this.getEndpoint(), sink,type);
        }
    }

    public synchronized String publish(SdpType sdpType,
                                       String sdpString, CallMediaStream endpoint) {
        registerOnIceCandidateEventListener(this.getOwner().getParticipantPublicId());
        if (endpoint != null)
            connect(endpoint.getEndpoint());
        String sdpResponse = processOfferOrAnswer(sdpType, sdpString);
        gatherCandidates();
        this.createdAt = System.currentTimeMillis();
        return sdpResponse;
    }


    public MediaOptions getMediaOptions() {
        return mediaOptions;
    }

    public void setMediaOptions(MediaOptions mediaOptions) {
        this.mediaOptions = mediaOptions;
    }
}
