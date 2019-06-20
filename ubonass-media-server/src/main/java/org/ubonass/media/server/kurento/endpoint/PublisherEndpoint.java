
package org.ubonass.media.server.kurento.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.MediaOptions;
import org.ubonass.media.server.kurento.TrackType;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.utils.JsonUtils;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Publisher aspect of the {@link MediaEndpoint}.
 *
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class PublisherEndpoint extends MediaEndpoint {

    private final static Logger log = LoggerFactory.getLogger(PublisherEndpoint.class);

    protected MediaOptions mediaOptions;

    private PassThrough passThru = null;
    private ListenerSubscription passThruSubscription = null;

    private GenericMediaElement filter;
    private Map<String, Set<String>> subscribersToFilterEvents = new ConcurrentHashMap<>();
    private Map<String, ListenerSubscription> filterListeners = new ConcurrentHashMap<>();

    private Map<String, MediaElement> elements = new HashMap<String, MediaElement>();
    private LinkedList<String> elementIds = new LinkedList<String>();
    private boolean connected = false;

    private Map<String, ListenerSubscription> elementsErrorSubscriptions = new HashMap<String, ListenerSubscription>();

    public PublisherEndpoint(boolean web,
                             KurentoParticipant owner,
                             String endpointName,
                             MediaPipeline pipeline,
                             CloudMediaConfig cloudMediaConfig) {
        super(web,owner, endpointName, pipeline, cloudMediaConfig, log);
    }

    @Override
    protected void internalEndpointInitialization(final CountDownLatch endpointLatch) {
        super.internalEndpointInitialization(endpointLatch);
        passThru = new PassThrough.Builder(getPipeline()).build();
        passThruSubscription = registerElemErrListener(passThru);
    }

    @Override
    public synchronized void unregisterErrorListeners() {
        super.unregisterErrorListeners();
        unregisterElementErrListener(passThru, passThruSubscription);
        for (String elemId : elementIds) {
            unregisterElementErrListener(elements.get(elemId), elementsErrorSubscriptions.remove(elemId));
        }
    }

    /**
     * @return all media elements created for this publisher, except for the main
     * element ( {@link WebRtcEndpoint})
     */
    public synchronized Collection<MediaElement> getMediaElements() {
        if (passThru != null) {
            elements.put(passThru.getId(), passThru);
        }
        return elements.values();
    }

    public GenericMediaElement getFilter() {
        return this.filter;
    }

    public boolean isListenerAddedToFilterEvent(String eventType) {
        return (this.subscribersToFilterEvents.containsKey(eventType)
                && this.filterListeners.containsKey(eventType));
    }

    public Set<String> getPartipantsListentingToFilterEvent(String eventType) {
        return this.subscribersToFilterEvents.get(eventType);
    }

    public boolean storeListener(String eventType, ListenerSubscription listener) {
        return (this.filterListeners.putIfAbsent(eventType, listener) == null);
    }

    public ListenerSubscription removeListener(String eventType) {
        // Clean all participant subscriptions to this event
        this.subscribersToFilterEvents.remove(eventType);
        // Clean ListenerSubscription object for this event
        return this.filterListeners.remove(eventType);
    }

    public void addParticipantAsListenerOfFilterEvent(String eventType, String participantPublicId) {
        this.subscribersToFilterEvents.putIfAbsent(eventType, new HashSet<>());
        this.subscribersToFilterEvents.get(eventType).add(participantPublicId);
    }

    public boolean removeParticipantAsListenerOfFilterEvent(String eventType, String participantPublicId) {
        if (!this.subscribersToFilterEvents.containsKey(eventType)) {
            String streamId = this.getStreamId();
            log.error("Request to removeFilterEventListener to stream {} gone wrong: Filter {} has no listener added",
                    streamId, eventType);
            throw new CloudMediaException(Code.FILTER_EVENT_LISTENER_NOT_FOUND,
                    "Request to removeFilterEventListener to stream " + streamId + " gone wrong: Filter " + eventType
                            + " has no listener added");
        }
        this.subscribersToFilterEvents.computeIfPresent(eventType, (type, subs) -> {
            subs.remove(participantPublicId);
            return subs;
        });
        return this.subscribersToFilterEvents.get(eventType).isEmpty();
    }

    public void cleanAllFilterListeners() {
        for (String eventType : this.subscribersToFilterEvents.keySet()) {
            this.removeListener(eventType);
        }
    }

    public void startTransmission() {
        log.info("start Transmission");
        gatherCandidates();
    }

    /**
     * Initializes this media endpoint for publishing media and processes the SDP
     * offer or answer. If the internal endpoint is an {@link WebRtcEndpoint}, it
     * first registers an event listener for the ICE candidates and instructs the
     * endpoint to start gathering the candidates. If required, it connects to
     * itself (after applying the intermediate media elements and the
     * {@link PassThrough}) to allow loopback of the media stream.
     *
     * @param sdpType                indicates the type of the sdpString (offer or
     *                               answer)
     * @param sdpString              offer or answer from the remote peer
     * @param doLoopback             loopback flag
     * @param loopbackAlternativeSrc alternative loopback source
     * @param loopbackConnectionType how to connect the loopback source
     * @return the SDP response (the answer if processing an offer SDP, otherwise is
     * the updated offer generated previously by this endpoint)
     */
    public synchronized String publish(SdpType sdpType,
                                       String sdpString,
                                       boolean doLoopback,
                                       MediaElement loopbackAlternativeSrc,
                                       MediaType loopbackConnectionType) {
        registerOnIceCandidateEventListener(this.getOwner().getParticipantPublicId());
        if (doLoopback) {
            if (loopbackAlternativeSrc == null) {
                connect(this.getEndpoint(), loopbackConnectionType);
            } else {
                connectAltLoopbackSrc(loopbackAlternativeSrc, loopbackConnectionType);
            }
        } else {
            innerConnect();
        }
        String sdpResponse = processOfferOrAnswer(sdpType,sdpString);

        //gatherCandidates();
        this.createdAt = System.currentTimeMillis();
        return sdpResponse;
    }

    public synchronized String preparePublishConnection() {
        return generateOffer();
    }

    /**
     * 只需要将其他的MediaElement和passThru相连接就好了
     * @param sink
     */
    public synchronized void connect(MediaElement sink) {
        if (!connected) {
            innerConnect();
        }
        //internalSinkConnect(passThru, sink);
        getEndpoint().connect(sink);
    }
    /**
     * 指定连接类型
     * @param sink
     * @param type
     */
    public synchronized void connect(MediaElement sink, MediaType type) {
        if (!connected) {
            innerConnect();
        }
        internalSinkConnect(passThru, sink, type);
    }

    public synchronized void disconnectFrom(MediaElement sink) {
        internalSinkDisconnect(passThru, sink);
    }

    public synchronized void disconnectFrom(MediaElement sink, MediaType type) {
        internalSinkDisconnect(passThru, sink, type);
    }

    /**
     * Changes the media passing through a chain of media elements by applying the
     * specified element/shaper. The element is plugged into the stream only if the
     * chain has been initialized (a.k.a. media streaming has started), otherwise it
     * is left ready for when the connections between elements will materialize and
     * the streaming begins.
     *
     * @param shaper {@link MediaElement} that will be linked to the end of the
     *               chain (e.g. a filter)
     * @return the element's id
     * @throws CloudMediaException if thrown, the media element was not added
     */
    public String apply(GenericMediaElement shaper) throws CloudMediaException {
        return apply(shaper, null);
    }

    /**
     * Same as {@link #apply(MediaElement)}, can specify the media type that will be
     * streamed through the shaper element.
     *
     * @param shaper {@link MediaElement} that will be linked to the end of the
     *               chain (e.g. a filter)
     * @param type   indicates which type of media will be connected to the shaper
     *               ({@link MediaType}), if null then the connection is mixed
     * @return the element's id
     * @throws CloudMediaException if thrown, the media element was not added
     */
    public synchronized String apply(GenericMediaElement shaper, MediaType type) throws CloudMediaException {
        String id = shaper.getId();
        if (id == null) {
            throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                    "Unable to connect media element with null id");
        }
        if (elements.containsKey(id)) {
            throw new CloudMediaException(Code.MEDIA_WEBRTC_ENDPOINT_ERROR_CODE,
                    "This endpoint already has a media element with id " + id);
        }
        MediaElement first = null;
        if (!elementIds.isEmpty()) {
            first = elements.get(elementIds.getFirst());
        }
        if (connected) {
            if (first != null) {
                internalSinkConnect(first, shaper, type);
            } else {
                internalSinkConnect(this.getEndpoint(), shaper, type);
            }
            internalSinkConnect(shaper, passThru, type);
        }
        elementIds.addFirst(id);
        elements.put(id, shaper);

        this.filter = shaper;

        elementsErrorSubscriptions.put(id, registerElemErrListener(shaper));
        return id;
    }

    /**
     * Removes the media element object found from the media chain structure. The
     * object is released. If the chain is connected, both adjacent remaining
     * elements will be interconnected.
     *
     * @param shaper {@link MediaElement} that will be removed from the chain
     * @throws CloudMediaException if thrown, the media element was not removed
     */
    public synchronized void revert(MediaElement shaper) throws CloudMediaException {
        revert(shaper, true);
    }

    public synchronized void revert(MediaElement shaper, boolean releaseElement) throws CloudMediaException {
        final String elementId = shaper.getId();
        if (!elements.containsKey(elementId)) {
            throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                    "This endpoint (" + getEndpointName() + ") has no media element with id " + elementId);
        }

        MediaElement element = elements.remove(elementId);
        unregisterElementErrListener(element, elementsErrorSubscriptions.remove(elementId));

        // careful, the order in the elems list is reverted
        if (connected) {
            String nextId = getNext(elementId);
            String prevId = getPrevious(elementId);
            // next connects to prev
            MediaElement prev = null;
            MediaElement next = null;
            if (nextId != null) {
                next = elements.get(nextId);
            } else {
                next = this.getEndpoint();
            }
            if (prevId != null) {
                prev = elements.get(prevId);
            } else {
                prev = passThru;
            }
            internalSinkConnect(next, prev);
        }
        elementIds.remove(elementId);
        if (releaseElement) {
            element.release(new Continuation<Void>() {
                @Override
                public void onSuccess(Void result) throws Exception {
                    log.trace("EP {}: Released media element {}", getEndpointName(), elementId);
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    log.error("EP {}: Failed to release media element {}", getEndpointName(), elementId, cause);
                }
            });
        }
        this.filter = null;
    }

    public JsonElement execMethod(String method, JsonObject params) throws CloudMediaException {
        Props props = new JsonUtils().fromJsonObjectToProps(params);
        return (JsonElement) ((GenericMediaElement) this.filter).invoke(method, props);
    }

    public synchronized void mute(TrackType muteType) {
        MediaElement sink = passThru;
        if (!elements.isEmpty()) {
            String sinkId = elementIds.peekLast();
            if (!elements.containsKey(sinkId)) {
                throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                        "This endpoint (" + getEndpointName() + ") has no media element with id " + sinkId
                                + " (should've been connected to the internal ep)");
            }
            sink = elements.get(sinkId);
        } else {
            log.debug("Will mute connection of WebRTC and PassThrough (no other elems)");
        }
        switch (muteType) {
            case ALL:
                internalSinkDisconnect(this.getEndpoint(), sink);
                break;
            case AUDIO:
                internalSinkDisconnect(this.getEndpoint(), sink, MediaType.AUDIO);
                break;
            case VIDEO:
                internalSinkDisconnect(this.getEndpoint(), sink, MediaType.VIDEO);
                break;
        }
    }

    public synchronized void unmute(TrackType muteType) {
        MediaElement sink = passThru;
        if (!elements.isEmpty()) {
            String sinkId = elementIds.peekLast();
            if (!elements.containsKey(sinkId)) {
                throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                        "This endpoint (" + getEndpointName() + ") has no media element with id " + sinkId
                                + " (should've been connected to the internal ep)");
            }
            sink = elements.get(sinkId);
        } else {
            log.debug("Will unmute connection of WebRTC and PassThrough (no other elems)");
        }
        switch (muteType) {
            case ALL:
                internalSinkConnect(this.getEndpoint(), sink);
                break;
            case AUDIO:
                internalSinkConnect(this.getEndpoint(), sink, MediaType.AUDIO);
                break;
            case VIDEO:
                internalSinkConnect(this.getEndpoint(), sink, MediaType.VIDEO);
                break;
        }
    }

    private String getNext(String uid) {
        int idx = elementIds.indexOf(uid);
        if (idx < 0 || idx + 1 == elementIds.size()) {
            return null;
        }
        return elementIds.get(idx + 1);
    }

    private String getPrevious(String uid) {
        int idx = elementIds.indexOf(uid);
        if (idx <= 0) {
            return null;
        }
        return elementIds.get(idx - 1);
    }

    private void connectAltLoopbackSrc(MediaElement loopbackAlternativeSrc, MediaType loopbackConnectionType) {
        if (!connected) {
            innerConnect();
        }
        internalSinkConnect(loopbackAlternativeSrc, this.getEndpoint(), loopbackConnectionType);
    }

    private void innerConnect() {
        /*if (this.getEndpoint() == null) {
            throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                    "Can't connect null endpoint (ep: " + getEndpointName() + ")");
        }
        MediaElement current = this.getEndpoint();
        String prevId = elementIds.peekLast();
        while (prevId != null) {
            MediaElement prev = elements.get(prevId);
            if (prev == null) {
                throw new CloudMediaException(Code.MEDIA_ENDPOINT_ERROR_CODE,
                        "No media element with id " + prevId + " (ep: " + getEndpointName() + ")");
            }
            internalSinkConnect(current, prev);
            current = prev;
            prevId = getPrevious(prevId);
        }
        internalSinkConnect(current, passThru);*/
        connected = true;
    }


    /*@Override
    public PublisherEndpoint getPublisher() {
        return this;
    }*/

    public MediaOptions getMediaOptions() {
        return mediaOptions;
    }

    public void setMediaOptions(MediaOptions mediaOptions) {
        this.mediaOptions = mediaOptions;
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = super.toJson();
        json.addProperty("streamId", this.getStreamId());
        json.add("mediaOptions", this.mediaOptions.toJson());
        return json;
    }

    @Override
    public JsonObject withStatsToJson() {
        JsonObject json = super.withStatsToJson();
        JsonObject toJson = this.toJson();
        for (Entry<String, JsonElement> entry : toJson.entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        return json;
    }

    public String filterCollectionsToString() {
        return "{filter: " + ((this.filter != null) ? this.filter.getName() : "null") + ", listener: "
                + this.filterListeners.toString() + ", subscribers: " + this.subscribersToFilterEvents.toString() + "}";
    }

}
