
package org.ubonass.media.server.kurento.endpoint;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.core.Token;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.kurento.core.KurentoTokenOptions;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * {@link WebRtcEndpoint} wrapper that supports buffering of
 * {@link IceCandidate}s until the {@link WebRtcEndpoint} is created.
 * Connections to other peers are opened using the corresponding method of the
 * internal rtpEndpoint.
 *
 * @author Pablo Fuente (pablofuenteperez@gmail.com)
 */
public abstract class MediaEndpoint {
    private static Logger log;
    private CloudMediaConfig cloudMediaConfig;

    private boolean web = false;

    private WebRtcEndpoint webEndpoint = null;
    private RtpEndpoint endpoint = null;

    private final int maxRecvKbps;
    private final int minRecvKbps;
    private final int maxSendKbps;
    private final int minSendKbps;

    private KurentoParticipant owner;
    protected String endpointName; // KMS media object identifier. Unique for every MediaEndpoint
    protected String streamId; // OpenVidu Stream identifier. Common property for a
    // PublisherEndpoint->SubscriberEndpoint flow. Equal to endpointName for
    // PublisherEndpoints, different for SubscriberEndpoints
    protected Long createdAt; // Timestamp when this [publisher / subscriber] started [publishing / receiving]

    private MediaPipeline pipeline = null;
    private ListenerSubscription endpointSubscription = null;

    private final List<IceCandidate> receivedCandidateList = new LinkedList<IceCandidate>();
    private LinkedList<IceCandidate> candidates = new LinkedList<IceCandidate>();

    public String selectedLocalIceCandidate;
    public String selectedRemoteIceCandidate;
    public Queue<KmsEvent> kmsEvents = new ConcurrentLinkedQueue<>();
    public Future<?> kmsWebrtcStatsThread;

    /**
     * Constructor to set the owner, the endpoint's name and the media pipeline.
     *
     * @param web
     * @param owner
     * @param endpointName
     * @param pipeline
     * @param log
     */
    public MediaEndpoint(boolean web,
                         KurentoParticipant owner,
                         String endpointName,
                         MediaPipeline pipeline,
                         CloudMediaConfig cloudMediaConfig,
                         Logger log) {
        if (log == null) {
            MediaEndpoint.log = LoggerFactory.getLogger(MediaEndpoint.class);
        } else {
            MediaEndpoint.log = log;
        }
        this.web = web;
        this.owner = owner;
        this.setEndpointName(endpointName);
        this.setMediaPipeline(pipeline);

        this.cloudMediaConfig = cloudMediaConfig;

        Token token = this.owner.getToken();
        if (token != null && token.getKurentoTokenOptions() != null) {
            KurentoTokenOptions kurentoTokenOptions = token.getKurentoTokenOptions();
            this.maxRecvKbps = kurentoTokenOptions.getVideoMaxRecvBandwidth() != null
                    ? kurentoTokenOptions.getVideoMaxRecvBandwidth()
                    : this.cloudMediaConfig.getVideoMaxRecvBandwidth();
            this.minRecvKbps = kurentoTokenOptions.getVideoMinRecvBandwidth() != null
                    ? kurentoTokenOptions.getVideoMinRecvBandwidth()
                    : this.cloudMediaConfig.getVideoMinRecvBandwidth();
            this.maxSendKbps = kurentoTokenOptions.getVideoMaxSendBandwidth() != null
                    ? kurentoTokenOptions.getVideoMaxSendBandwidth()
                    : this.cloudMediaConfig.getVideoMaxSendBandwidth();
            this.minSendKbps = kurentoTokenOptions.getVideoMinSendBandwidth() != null
                    ? kurentoTokenOptions.getVideoMinSendBandwidth()
                    : this.cloudMediaConfig.getVideoMinSendBandwidth();
        } else {
            this.maxRecvKbps = this.cloudMediaConfig.getVideoMaxRecvBandwidth();
            this.minRecvKbps = this.cloudMediaConfig.getVideoMinRecvBandwidth();
            this.maxSendKbps = this.cloudMediaConfig.getVideoMaxSendBandwidth();
            this.minSendKbps = this.cloudMediaConfig.getVideoMinSendBandwidth();
        }
    }

    public boolean isWeb() {
        return web;
    }

    /**
     * @return the user session that created this endpoint
     */
    public Participant getOwner() {
        return owner;
    }

    /**
     * @return the internal endpoint ({@link RtpEndpoint} or {@link WebRtcEndpoint})
     */
    public SdpEndpoint getEndpoint() {
        if (this.isWeb()) {
            return this.webEndpoint;
        } else {
            return this.endpoint;
        }
    }

    public long createdAt() {
        return this.createdAt;
    }

    public WebRtcEndpoint getWebEndpoint() {
        return webEndpoint;
    }

    protected RtpEndpoint getRtpEndpoint() {
        return endpoint;
    }

    /**
     * If this object doesn't have a {@link WebRtcEndpoint}, it is created in a
     * thread-safe way using the internal {@link MediaPipeline}. Otherwise no
     * actions are taken. It also registers an error listener for the endpoint and
     * for any additional media elements.
     *
     * @param endpointLatch latch whose countdown is performed when the asynchronous
     *                      call to build the {@link WebRtcEndpoint} returns
     * @return the existing endpoint, if any
     */
    public synchronized SdpEndpoint createEndpoint(CountDownLatch endpointLatch) {
        SdpEndpoint old = this.getEndpoint();
        if (old == null) {
            internalEndpointInitialization(endpointLatch);
        } else {
            endpointLatch.countDown();
        }
        if (this.isWeb()) {
            while (!candidates.isEmpty()) {
                internalAddIceCandidate(candidates.removeFirst());
            }
        }
        return old;
    }

    /**
     * @return the pipeline
     */
    public MediaPipeline getPipeline() {
        return this.pipeline;
    }

    /**
     * Sets the {@link MediaPipeline} used to create the internal
     * {@link WebRtcEndpoint}.
     *
     * @param pipeline the {@link MediaPipeline}
     */
    public void setMediaPipeline(MediaPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public String getEndpointName() {
        if (endpointName == null) {
            endpointName = this.getEndpoint().getName();
        }
        return endpointName;
    }

    public void setEndpointName(String endpointName) {
        this.endpointName = endpointName;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    /**
     * Unregisters all error listeners created for media elements owned by this
     * instance.
     */
    public synchronized void unregisterErrorListeners() {
        unregisterElementErrListener(endpoint, endpointSubscription);
    }

    /**
     * Creates the endpoint (RTP or WebRTC) and any other additional elements (if
     * needed).
     *
     * @param endpointLatch
     */
    protected void internalEndpointInitialization(final CountDownLatch endpointLatch) {
        if (this.isWeb()) {
            WebRtcEndpoint.Builder builder = new WebRtcEndpoint.Builder(pipeline);
            /*
             * if (this.dataChannels) { builder.useDataChannels(); }
             */
            builder.buildAsync(new Continuation<WebRtcEndpoint>() {
                @Override
                public void onSuccess(WebRtcEndpoint result) throws Exception {
                    webEndpoint = result;

                    webEndpoint.setMaxVideoRecvBandwidth(maxRecvKbps);
                    webEndpoint.setMinVideoRecvBandwidth(minRecvKbps);
                    webEndpoint.setMaxVideoSendBandwidth(maxSendKbps);
                    webEndpoint.setMinVideoSendBandwidth(minSendKbps);

                    endpointLatch.countDown();
                    log.trace("EP {}: Created a new WebRtcEndpoint", endpointName);
                    endpointSubscription = registerElemErrListener(webEndpoint);
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    endpointLatch.countDown();
                    log.error("EP {}: Failed to create a new WebRtcEndpoint", endpointName, cause);
                }
            });
        } else {
            new RtpEndpoint.Builder(pipeline).buildAsync(new Continuation<RtpEndpoint>() {
                @Override
                public void onSuccess(RtpEndpoint result) throws Exception {
                    endpoint = result;
                    endpointLatch.countDown();
                    log.trace("EP {}: Created a new RtpEndpoint", endpointName);
                    endpointSubscription = registerElemErrListener(endpoint);
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    endpointLatch.countDown();
                    log.error("EP {}: Failed to create a new RtpEndpoint", endpointName, cause);
                }
            });
        }
    }

    /**
     * Add a new {@link IceCandidate} received gathered by the remote peer of this
     * {@link WebRtcEndpoint}.
     *
     * @param candidate the remote candidate
     */
    public synchronized void addIceCandidate(IceCandidate candidate) throws CloudMediaException {
        if (!this.isWeb()) {
            throw new CloudMediaException(Code.MEDIA_NOT_A_WEB_ENDPOINT_ERROR_CODE, "Operation not supported");
        }
        if (webEndpoint == null) {
            candidates.addLast(candidate);
        } else {
            internalAddIceCandidate(candidate);
        }
    }

    /**
     * Registers a listener for when the {@link MediaElement} triggers an
     * {@link ErrorEvent}. Notifies the owner with the error.
     *
     * @param element the {@link MediaElement}
     * @return {@link ListenerSubscription} that can be used to deregister the
     * listener
     */
    protected ListenerSubscription registerElemErrListener(MediaElement element) {
        return element.addErrorListener(new EventListener<ErrorEvent>() {
            @Override
            public void onEvent(ErrorEvent event) {
                owner.sendMediaError(event);
            }
        });
    }

    /**
     * Unregisters the error listener from the media element using the provided
     * subscription.
     *
     * @param element      the {@link MediaElement}
     * @param subscription the associated {@link ListenerSubscription}
     */
    protected void unregisterElementErrListener(MediaElement element, final ListenerSubscription subscription) {
        if (element == null || subscription == null) {
            return;
        }
        element.removeErrorListener(subscription);
    }

    protected String processOfferOrAnswer(SdpType sdpType,
                                          String sdpString) {
        String sdpResponse = null;
        switch (sdpType) {
            case ANSWER:
                sdpResponse = processAnswer(sdpString);
                break;
            case OFFER:
                sdpResponse = processOffer(sdpString);
                break;
            default:
                throw new CloudMediaException(Code.MEDIA_SDP_ERROR_CODE, "Sdp type not supported: " + sdpType);
        }
        return sdpResponse;
    }

    /**
     * Orders the internal endpoint ({@link RtpEndpoint} or {@link WebRtcEndpoint})
     * to process the offer String.
     *
     * @param offer String with the Sdp offer
     * @return the Sdp answer
     * @see SdpEndpoint#processOffer(String)
     */
    protected String processOffer(String offer) throws CloudMediaException {
        if (this.isWeb()) {
            if (webEndpoint == null) {
                throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                        "Can't process offer when WebRtcEndpoint is null (ep: " + endpointName + ")");
            }
            return webEndpoint.processOffer(offer);
        } else {
            if (endpoint == null) {
                throw new CloudMediaException(Code.MEDIA_RTP_ENDPOINT_ERROR_CODE,
                        "Can't process offer when RtpEndpoint is null (ep: " + endpointName + ")");
            }
            return endpoint.processOffer(offer);
        }
    }

    /**
     * Orders the internal endpoint ({@link RtpEndpoint} or {@link WebRtcEndpoint})
     * to generate the offer String that can be used to initiate a connection.
     *
     * @return the Sdp offer
     * @see SdpEndpoint#generateOffer()
     */
    protected String generateOffer() throws CloudMediaException {
        if (this.isWeb()) {
            if (webEndpoint == null) {
                throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                        "Can't generate offer when WebRtcEndpoint is null (ep: " + endpointName + ")");
            }
            return webEndpoint.generateOffer();
        } else {
            if (endpoint == null) {
                throw new CloudMediaException(Code.MEDIA_RTP_ENDPOINT_ERROR_CODE,
                        "Can't generate offer when RtpEndpoint is null (ep: " + endpointName + ")");
            }
            return endpoint.generateOffer();
        }
    }

    /**
     * Orders the internal endpoint ({@link RtpEndpoint} or {@link WebRtcEndpoint})
     * to process the answer String.
     *
     * @param answer String with the Sdp answer from remote
     * @return the updated Sdp offer, based on the received answer
     * @see SdpEndpoint#processAnswer(String)
     */
    protected String processAnswer(String answer) throws CloudMediaException {
        if (this.isWeb()) {
            if (webEndpoint == null) {
                throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                        "Can't process answer when WebRtcEndpoint is null (ep: " + endpointName + ")");
            }
            return webEndpoint.processAnswer(answer);
        } else {
            if (endpoint == null) {
                throw new CloudMediaException(Code.MEDIA_RTP_ENDPOINT_ERROR_CODE,
                        "Can't process answer when RtpEndpoint is null (ep: " + endpointName + ")");
            }
            return endpoint.processAnswer(answer);
        }
    }

    /**
     * If supported, it registers a listener for when a new {@link IceCandidate} is
     * gathered by the internal endpoint ({@link WebRtcEndpoint}) and sends it to
     * the remote User Agent as a notification using the messaging capabilities of
     * the {@link Participant}.
     *
     * @throws CloudMediaException if thrown, unable to register the listener
     * @see WebRtcEndpoint#addOnIceCandidateListener(org.kurento.client.EventListener)
     * @see Participant#sendIceCandidate(String, IceCandidate)
     */
    protected void registerOnIceCandidateEventListener(String senderPublicId) throws CloudMediaException {
        if (!this.isWeb()) {
            return;
        }
        if (webEndpoint == null) {
            throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                    "Can't register event listener for null WebRtcEndpoint (ep: " + endpointName + ")");
        }
        webEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
            @Override
            public void onEvent(OnIceCandidateEvent event) {
                owner.sendIceCandidate(senderPublicId, endpointName, event.getCandidate());
            }
        });
    }

    /**
     * If supported, it instructs the internal endpoint to start gathering
     * {@link IceCandidate}s.
     */
    protected void gatherCandidates() throws CloudMediaException {
        if (!this.isWeb()) {
            return;
        }
        if (webEndpoint == null) {
            throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                    "Can't start gathering ICE candidates on null WebRtcEndpoint (ep: " + endpointName + ")");
        }
        webEndpoint.gatherCandidates(new Continuation<Void>() {
            @Override
            public void onSuccess(Void result) throws Exception {
                log.trace("EP {}: Internal endpoint started to gather candidates", endpointName);
            }

            @Override
            public void onError(Throwable cause) throws Exception {
                log.warn("EP {}: Internal endpoint failed to start gathering candidates", endpointName, cause);
            }
        });
    }

    private void internalAddIceCandidate(IceCandidate candidate) throws CloudMediaException {
        if (webEndpoint == null) {
            throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                    "Can't add existing ICE candidates to null WebRtcEndpoint (ep: " + endpointName + ")");
        }
        this.receivedCandidateList.add(candidate);
        this.webEndpoint.addIceCandidate(candidate, new Continuation<Void>() {
            @Override
            public void onSuccess(Void result) throws Exception {
                log.trace("Ice candidate added to the internal endpoint");
            }

            @Override
            public void onError(Throwable cause) throws Exception {
                log.warn("EP {}: Failed to add ice candidate to the internal endpoint", endpointName, cause);
            }
        });
    }


    protected void internalSinkConnect(final MediaElement source, final MediaElement sink) {
        source.connect(sink, new Continuation<Void>() {
            @Override
            public void onSuccess(Void result) throws Exception {
                log.info("EP {}: Elements have been connected (source {} -> sink {})", getEndpointName(),
                        source.getId(), sink.getId());
            }

            @Override
            public void onError(Throwable cause) throws Exception {
                log.warn("EP {}: Failed to connect media elements (source {} -> sink {})", getEndpointName(),
                        source.getId(), sink.getId(), cause);
            }
        });
    }

    /**
     * Same as {@link #internalSinkConnect(MediaElement, MediaElement)}, but can
     * specify the type of the media that will be streamed.
     *
     * @param source
     * @param sink
     * @param type   if null,
     *               {@link #internalSinkConnect(MediaElement, MediaElement)} will
     *               be used instead
     * @see #internalSinkConnect(MediaElement, MediaElement)
     */
    protected void internalSinkConnect(final MediaElement source, final MediaElement sink, final MediaType type) {
        if (type == null) {
            internalSinkConnect(source, sink);
        } else {
            source.connect(sink, type, new Continuation<Void>() {
                @Override
                public void onSuccess(Void result) throws Exception {
                    log.info("EP {}: {} media elements have been connected (source {} -> sink {})", getEndpointName(),
                            type, source.getId(), sink.getId());
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    log.warn("EP {}: Failed to connect {} media elements (source {} -> sink {})", getEndpointName(),
                            type, source.getId(), sink.getId(), cause);
                }
            });
        }
    }

    protected void internalSinkDisconnect(final MediaElement source, final MediaElement sink) {
        source.disconnect(sink, new Continuation<Void>() {
            @Override
            public void onSuccess(Void result) throws Exception {
                log.info("EP {}: Elements have been disconnected (source {} -> sink {})", getEndpointName(),
                        source.getId(), sink.getId());
            }

            @Override
            public void onError(Throwable cause) throws Exception {
                log.warn("EP {}: Failed to disconnect media elements (source {} -> sink {})", getEndpointName(),
                        source.getId(), sink.getId(), cause);
            }
        });
    }

    /**
     * Same as {@link #internalSinkDisconnect(MediaElement, MediaElement)}, but can
     * specify the type of the media that will be disconnected.
     *
     * @param source
     * @param sink
     * @param type   if null,
     *               {@link #internalSinkConnect(MediaElement, MediaElement)} will
     *               be used instead
     * @see #internalSinkConnect(MediaElement, MediaElement)
     */
    protected void internalSinkDisconnect(final MediaElement source, final MediaElement sink, final MediaType type) {
        if (type == null) {
            internalSinkDisconnect(source, sink);
        } else {
            source.disconnect(sink, type, new Continuation<Void>() {
                @Override
                public void onSuccess(Void result) throws Exception {
                    log.info("EP {}: {} media elements have been disconnected (source {} -> sink {})",
                            getEndpointName(), type, source.getId(), sink.getId());
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    log.warn("EP {}: Failed to disconnect {} media elements (source {} -> sink {})", getEndpointName(),
                            type, source.getId(), sink.getId(), cause);
                }
            });
        }
    }


    public abstract PublisherEndpoint getPublisher();


    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("createdAt", this.createdAt);
        return json;
    }

    public JsonObject withStatsToJson() {
        JsonObject json = new JsonObject();
        json.addProperty("createdAt", this.createdAt);
        json.addProperty("webrtcEndpointName", this.getEndpointName());
        json.addProperty("remoteSdp", this.getEndpoint().getRemoteSessionDescriptor());
        json.addProperty("localSdp", this.getEndpoint().getLocalSessionDescriptor());
        json.add("receivedCandidates", new GsonBuilder().create().toJsonTree(this.receivedCandidateList));
        json.addProperty("localCandidate", this.selectedLocalIceCandidate);
        json.addProperty("remoteCandidate", this.selectedRemoteIceCandidate);

        JsonArray jsonArray = new JsonArray();
        this.kmsEvents.forEach(ev -> {
            // Remove unwanted properties
            JsonObject j = ev.toJson();
            j.remove("session");
            j.remove("user");
            j.remove("connection");
            j.remove("endpoint");
            j.remove("timestampMillis");
            jsonArray.add(j);
        });
        json.add("events", jsonArray);

        return json;
    }
}
