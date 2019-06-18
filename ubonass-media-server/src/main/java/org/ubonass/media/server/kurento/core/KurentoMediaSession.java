

package org.ubonass.media.server.kurento.core;

import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.java.client.CloudMediaRole;
import org.ubonass.media.server.core.EndReason;
import org.ubonass.media.server.core.MediaSession;
import org.ubonass.media.server.core.Participant;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Pablo Fuente (pablofuenteperez@gmail.com)
 */
public class KurentoMediaSession extends MediaSession {

    private final static Logger log = LoggerFactory.getLogger(MediaSession.class);
    public static final int ASYNC_LATCH_TIMEOUT = 30;

    private MediaPipeline pipeline;
    private CountDownLatch pipelineLatch = new CountDownLatch(1);

    private KurentoClient kurentoClient;
    private KurentoSessionEventsHandler kurentoSessionHandler;
    private KurentoParticipantEndpointConfig kurentoEndpointConfig;

    private final ConcurrentHashMap<String, String> filterStates = new ConcurrentHashMap<>();

    private Object pipelineCreateLock = new Object();
    private Object pipelineReleaseLock = new Object();
    private boolean destroyKurentoClient;

    public final ConcurrentHashMap<String, String> publishedStreamIds = new ConcurrentHashMap<>();

    public KurentoMediaSession(MediaSession sessionNotActive,
                               KurentoClient kurentoClient,
                               KurentoSessionEventsHandler kurentoSessionHandler,
                               KurentoParticipantEndpointConfig kurentoEndpointConfig,
                               boolean destroyKurentoClient) {
        super(sessionNotActive);
        this.kurentoClient = kurentoClient;
        this.destroyKurentoClient = destroyKurentoClient;
        this.kurentoSessionHandler = kurentoSessionHandler;
        this.kurentoEndpointConfig = kurentoEndpointConfig;
        log.debug("New SESSION instance with id '{}'", sessionId);
    }

    @Override
    public void join(Participant participant,boolean remoteNeed) {
        checkClosed();
        createPipeline();
        KurentoParticipant kurentoParticipant =
                new KurentoParticipant(participant, this, this.kurentoEndpointConfig,
                        this.cloudMediaConfig/*, this.recordingManager*/, false,remoteNeed);
        participants.put(participant.getParticipantPrivatetId(), kurentoParticipant);

        filterStates.forEach((filterId, state) -> {
            log.info("Adding filter {}", filterId);
            kurentoSessionHandler.updateFilter(sessionId, participant, filterId, state);
        });

        log.info("SESSION {}: Added participant {}", sessionId, participant);
        //modify by jeffrey
		/*if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(participant.getParticipantPublicId())) {
			kurentoEndpointConfig.getCdr().recordParticipantJoined(participant, sessionId);
		}*/
    }

    /**
     * for one to one call
     *
     * @param participant
     */
    public void createCallMediaStream(Participant participant,boolean remoteNeed) {
        checkClosed();
        createPipeline();
        KurentoParticipant kurentoParticipant =
                new KurentoParticipant(participant, this, this.kurentoEndpointConfig,
                        this.cloudMediaConfig/*, this.recordingManager*/, true,remoteNeed);
        participants.put(participant.getParticipantPrivatetId(), kurentoParticipant);
        filterStates.forEach((filterId, state) -> {
            log.info("Adding filter {}", filterId);
            kurentoSessionHandler.updateFilter(sessionId, participant, filterId, state);
        });
    }



    public void newPublisher(Participant participant) {
        registerPublisher();

        // pre-load endpoints to recv video from the new publisher
        for (Participant p : participants.values()) {
            if (participant.equals(p)) {
                continue;
            }
            ((KurentoParticipant) p).getNewOrExistingSubscriber(participant.getParticipantPublicId());
        }

        log.debug("SESSION {}: Virtually subscribed other participants {} to new publisher {}", sessionId,
                participants.values(), participant.getParticipantPublicId());
    }

    public void cancelPublisher(Participant participant, EndReason reason) {
        // Cancel all subscribers for this publisher
        for (Participant subscriber : participants.values()) {
            if (participant.equals(subscriber)) {
                continue;
            }
            ((KurentoParticipant) subscriber).cancelReceivingMedia(participant.getParticipantPublicId(), reason);
        }

        log.debug("SESSION {}: Unsubscribed other participants {} from the publisher {}", sessionId,
                participants.values(), participant.getParticipantPublicId());

    }

    @Override
    public void leave(String participantPrivateId, EndReason reason) throws CloudMediaException {

        checkClosed();

        KurentoParticipant participant = (KurentoParticipant) participants.get(participantPrivateId);
        if (participant == null) {
            throw new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE, "Participant with private id "
                    + participantPrivateId + " not found in session '" + sessionId + "'");
        }
        participant.releaseAllFilters();

        log.info("PARTICIPANT {}: Leaving session {}", participant.getParticipantPublicId(), this.sessionId);

        this.removeParticipant(participant, reason);
        participant.close(reason, true);
    }

    @Override
    public boolean close(EndReason reason) {
        if (!closed) {

            for (Participant participant : participants.values()) {
                ((KurentoParticipant) participant).releaseAllFilters();
                ((KurentoParticipant) participant).close(reason, true);
            }

            participants.clear();

            closePipeline(null);

            log.debug("Session {} closed", this.sessionId);

            if (destroyKurentoClient) {
                kurentoClient.destroy();
            }

            this.closed = true;
            return true;
        } else {
            log.warn("Closing an already closed session '{}'", this.sessionId);
            return false;
        }
    }

    public void sendIceCandidate(String participantPrivateId, String senderPublicId, String endpointName,
                                 IceCandidate candidate) {
        this.kurentoSessionHandler.onIceCandidate(sessionId, participantPrivateId, senderPublicId, endpointName,
                candidate);
    }

    public void sendMediaError(String participantId, String description) {
        this.kurentoSessionHandler.onMediaElementError(sessionId, participantId, description);
    }

    private void removeParticipant(Participant participant, EndReason reason) {

        checkClosed();

        participants.remove(participant.getParticipantPrivatetId());

        log.debug("SESSION {}: Cancel receiving media from participant '{}' for other participant", this.sessionId,
                participant.getParticipantPublicId());
        for (Participant other : participants.values()) {
            ((KurentoParticipant) other).cancelReceivingMedia(participant.getParticipantPublicId(), reason);
        }
    }

    public MediaPipeline getPipeline() {
        try {
            pipelineLatch.await(KurentoMediaSession.ASYNC_LATCH_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return this.pipeline;
    }

    protected void createPipeline() {
        synchronized (pipelineCreateLock) {
            if (pipeline != null) {
                return;
            }
            log.info("SESSION {}: Creating MediaPipeline", sessionId);
            try {
                kurentoClient.createMediaPipeline(new Continuation<MediaPipeline>() {
                    @Override
                    public void onSuccess(MediaPipeline result) throws Exception {
                        pipeline = result;
                        pipelineLatch.countDown();
                        log.debug("SESSION {}: Created MediaPipeline", sessionId);
                    }

                    @Override
                    public void onError(Throwable cause) throws Exception {
                        pipelineLatch.countDown();
                        log.error("SESSION {}: Failed to create MediaPipeline", sessionId, cause);
                    }
                });
            } catch (Exception e) {
                log.error("Unable to create media pipeline for session '{}'", sessionId, e);
                pipelineLatch.countDown();
            }
            if (getPipeline() == null) {
                throw new CloudMediaException(Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
                        "Unable to create media pipeline for session '" + sessionId + "'");
            }

            pipeline.addErrorListener(new EventListener<ErrorEvent>() {
                @Override
                public void onEvent(ErrorEvent event) {
                    String desc = event.getType() + ": " + event.getDescription() + "(errCode=" + event.getErrorCode()
                            + ")";
                    log.warn("SESSION {}: Pipeline error encountered: {}", sessionId, desc);
                    kurentoSessionHandler.onPipelineError(sessionId, getParticipants(), desc);
                }
            });
        }
    }

    protected void closePipeline(Runnable callback) {
        synchronized (pipelineReleaseLock) {
            if (pipeline == null) {
                return;
            }
            getPipeline().release(new Continuation<Void>() {
                @Override
                public void onSuccess(Void result) throws Exception {
                    log.debug("SESSION {}: Released Pipeline", sessionId);
                    pipeline = null;
                    pipelineLatch = new CountDownLatch(1);
                    if (callback != null) {
                        callback.run();
                    }
                }

                @Override
                public void onError(Throwable cause) throws Exception {
                    log.warn("SESSION {}: Could not successfully release Pipeline", sessionId, cause);
                    pipeline = null;
                    pipelineLatch = new CountDownLatch(1);
                    if (callback != null) {
                        callback.run();
                    }
                }
            });
        }
    }

    public String getParticipantPrivateIdFromStreamId(String streamId) {
        return this.publishedStreamIds.get(streamId);
    }

    public void restartStatusInKurento() {

        log.info("Reseting remote media objects for active session {}", this.sessionId);

        //modify by jeffrey
        // Stop recording if session is being recorded
		/*if (recordingManager.sessionIsBeingRecorded(this.sessionId)) {
			Recording stoppedRecording = this.recordingManager.forceStopRecording(this,
					EndReason.mediaServerDisconnect);
			if (OutputMode.COMPOSED.equals(stoppedRecording.getOutputMode()) && stoppedRecording.hasVideo()) {
				recordingManager.getSessionManager().evictParticipant(
						this.getParticipantByPublicId(ProtocolElements.RECORDER_PARTICIPANT_PUBLICID), null, null,
						null);
			}
		}*/

        // Close all MediaEndpoints of participants
        this.getParticipants().forEach(p -> {
            KurentoParticipant kParticipant = (KurentoParticipant) p;
            final boolean wasStreaming = kParticipant.isStreaming();
            kParticipant.releaseAllFilters();
            kParticipant.close(EndReason.mediaServerDisconnect, false);
            if (wasStreaming) {
                kurentoSessionHandler.onUnpublishMedia(kParticipant, this.getParticipants(), null, null, null,
                        EndReason.mediaServerDisconnect);
            }
        });

        // Release pipeline, create a new one and prepare new PublisherEndpoints for
        // allowed users
        this.closePipeline(() -> {
            createPipeline();
            try {
                if (!pipelineLatch.await(20, TimeUnit.SECONDS)) {
                    throw new Exception("MediaPipleine was not created in 20 seconds");
                }
                getParticipants().forEach(p -> {
                    if (!CloudMediaRole.SUBSCRIBER.equals(p.getToken().getRole())) {
                        ((KurentoParticipant) p).resetPublisherEndpoint();
                    }
                });
            } catch (Exception e) {
                log.error("Error waiting to new MediaPipeline on KurentoMediaSession restart: {}", e.getMessage());
            }
        });
    }

}
