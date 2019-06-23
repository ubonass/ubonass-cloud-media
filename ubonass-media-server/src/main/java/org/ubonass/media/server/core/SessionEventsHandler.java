

package org.ubonass.media.server.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.CloudMediaRole;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.kurento.KurentoFilter;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class SessionEventsHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionEventsHandler.class);

    @Autowired
    protected RpcNotificationService rpcNotificationService;

    @Autowired
    protected ClusterRpcService clusterRpcService;

   /* @Autowired
    protected InfoHandler infoHandler;

    @Autowired
    protected CallDetailRecord CDR;
    */
    @Autowired
    protected CloudMediaConfig cloudMediaConfig;

    Map<String, RecordingInfo> recordingsStarted = new ConcurrentHashMap<>();

    ReentrantLock lock = new ReentrantLock();

    public void onSessionCreated(MediaSession session) {
        //modify by jeffrey
        /*CDR.recordSessionCreated(session);*/
    }

    public void onSessionClosed(String sessionId, EndReason reason) {
        //modify by jeffrey
        /*CDR.recordSessionDestroyed(sessionId, reason);*/
    }

    public void onCall(Participant participant, String calleeParticipantPublicId, String sdpAnswer, Integer transactionId) {
        if (sdpAnswer == null) return;

        JsonObject notifyInCallObject = new JsonObject();
        notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_FROMUSER_PARAM, participant.getParticipantPublicId());
        notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_SESSION_PARAM, participant.getSessionId());
        rpcNotificationService.sendNotificationByPublicId(
                calleeParticipantPublicId, ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);

        JsonObject result = new JsonObject();
        result.addProperty("method", ProtocolElements.CALL_METHOD);
        result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM, "OK");
        result.addProperty(ProtocolElements.CALL_SDPANSWER_PARAM, sdpAnswer);
        rpcNotificationService.sendResponse(
                participant.getParticipantPrivatetId(), transactionId, result);
    }

    public void onCallAccept(Participant participant,
                             String sdpAnswer,
                             Integer transactionId) {
        JsonObject connectedObject = new JsonObject();
        //startCommunication.addProperty("id", "startCommunication");
        connectedObject.addProperty(
                ProtocolElements.ONCALL_SDPANSWER_PARAM, sdpAnswer);
        connectedObject.addProperty(
                ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_CONNECTED);
        rpcNotificationService.sendNotification(
                participant.getParticipantPrivatetId(), ProtocolElements.ONCALL_METHOD, connectedObject);
    }

    public void onCallHangup(Participant participant, Collection<ClusterConnection> connections,Integer transactionId) {

        for (ClusterConnection connection : connections) {
            if (connection.getParticipantPrivateId().
                    equals(participant.getParticipantPrivatetId()) &&
                    clusterRpcService.isLocalHostMember(connection.getMemberId()))
                continue;

            JsonObject hangupObject = new JsonObject();
            hangupObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM,
                    ProtocolElements.ONCALL_EVENT_HANGUP);
            rpcNotificationService.sendNotificationByPublicId(
                    connection.getParticipantPublicId(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        }
    }

    public void onParticipantJoined(Participant participant, String sessionId, Set<Participant> existingParticipants,
                                    Integer transactionId, CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }

        JsonObject result = new JsonObject();
        JsonArray resultArray = new JsonArray();

        for (Participant existingParticipant : existingParticipants) {
            JsonObject participantJson = new JsonObject();
            participantJson.addProperty(ProtocolElements.JOINROOM_PEERID_PARAM,
                    existingParticipant.getParticipantPublicId());
            participantJson.addProperty(ProtocolElements.JOINROOM_PEERCREATEDAT_PARAM,
                    existingParticipant.getCreatedAt());

            // Metadata associated to each existing participant
            participantJson.addProperty(ProtocolElements.JOINROOM_METADATA_PARAM,
                    existingParticipant.getFullMetadata());

            if (existingParticipant.isStreaming()) {

                KurentoParticipant kParticipant = (KurentoParticipant) existingParticipant;

                JsonObject stream = new JsonObject();
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMID_PARAM,
                        existingParticipant.getPublisherStreamId());
                stream.addProperty(ProtocolElements.JOINROOM_PEERCREATEDAT_PARAM,
                        kParticipant.getPublisher().createdAt());
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMHASAUDIO_PARAM,
                        kParticipant.getPublisherMediaOptions().hasAudio);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMHASVIDEO_PARAM,
                        kParticipant.getPublisherMediaOptions().hasVideo);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMVIDEOACTIVE_PARAM,
                        kParticipant.getPublisherMediaOptions().videoActive);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMAUDIOACTIVE_PARAM,
                        kParticipant.getPublisherMediaOptions().audioActive);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMVIDEOACTIVE_PARAM,
                        kParticipant.getPublisherMediaOptions().videoActive);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMTYPEOFVIDEO_PARAM,
                        kParticipant.getPublisherMediaOptions().typeOfVideo);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMFRAMERATE_PARAM,
                        kParticipant.getPublisherMediaOptions().frameRate);
                stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMVIDEODIMENSIONS_PARAM,
                        kParticipant.getPublisherMediaOptions().videoDimensions);
                JsonElement filter = kParticipant.getPublisherMediaOptions().getFilter() != null
                        ? kParticipant.getPublisherMediaOptions().getFilter().toJson()
                        : new JsonObject();
                stream.add(ProtocolElements.JOINROOM_PEERSTREAMFILTER_PARAM, filter);

                JsonArray streamsArray = new JsonArray();
                streamsArray.add(stream);
                participantJson.add(ProtocolElements.JOINROOM_PEERSTREAMS_PARAM, streamsArray);
            }

            // Avoid emitting 'connectionCreated' event of existing RECORDER participant in
            // openvidu-browser in newly joined participants
            if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(existingParticipant.getParticipantPublicId())) {
                resultArray.add(participantJson);
            }

            // If RECORDER participant has joined do NOT send 'participantJoined'
            // notification to existing participants. 'recordingStarted' will be sent to all
            // existing participants when recorder first subscribe to a stream
            if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(participant.getParticipantPublicId())) {
                JsonObject notifParams = new JsonObject();

                // Metadata associated to new participant
                notifParams.addProperty(ProtocolElements.PARTICIPANTJOINED_USER_PARAM,
                        participant.getParticipantPublicId());
                notifParams.addProperty(ProtocolElements.PARTICIPANTJOINED_CREATEDAT_PARAM, participant.getCreatedAt());
                notifParams.addProperty(ProtocolElements.PARTICIPANTJOINED_METADATA_PARAM,
                        participant.getFullMetadata());

                rpcNotificationService.sendNotification(existingParticipant.getParticipantPrivatetId(),
                        ProtocolElements.PARTICIPANTJOINED_METHOD, notifParams);
            }
        }
        result.addProperty("method", ProtocolElements.JOINROOM_METHOD);//标识这是当前是joinRoom请求
        result.addProperty(ProtocolElements.PARTICIPANTJOINED_USER_PARAM, participant.getParticipantPublicId());
        result.addProperty(ProtocolElements.PARTICIPANTJOINED_CREATEDAT_PARAM, participant.getCreatedAt());
        result.addProperty(ProtocolElements.PARTICIPANTJOINED_METADATA_PARAM, participant.getFullMetadata());
        result.add("value", resultArray);

        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, result);
    }

    public void onParticipantLeft(Participant participant, String sessionId, Set<Participant> remainingParticipants,
                                  Integer transactionId, CloudMediaException error, EndReason reason) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }

        if (ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(participant.getParticipantPublicId())) {
            // RECORDER participant
            return;
        }

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.PARTICIPANTLEFT_NAME_PARAM, participant.getParticipantPublicId());
        params.addProperty(ProtocolElements.PARTICIPANTLEFT_REASON_PARAM, reason != null ? reason.name() : "");

        for (Participant p : remainingParticipants) {
            rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                    ProtocolElements.PARTICIPANTLEFT_METHOD, params);
        }

        if (transactionId != null) {
            // No response when the participant is forcibly evicted instead of voluntarily
            // leaving the session
            rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, new JsonObject());
        }
        //modify by jeffrey
        /*if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(participant.getParticipantPublicId())) {
            CDR.recordParticipantLeft(participant, sessionId, reason);
        }*/
    }

    public void onPublishMedia(Participant participant, String streamId, Long createdAt, String sessionId,
                               MediaOptions mediaOptions, String sdpAnswer, Set<Participant> participants, Integer transactionId,
                               CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }
        JsonObject result = new JsonObject();
        //modify by jeffrey
        result.addProperty("method", ProtocolElements.PUBLISHVIDEO_METHOD);
        result.addProperty(ProtocolElements.PUBLISHVIDEO_SDPANSWER_PARAM, sdpAnswer);
        result.addProperty(ProtocolElements.PUBLISHVIDEO_STREAMID_PARAM, streamId);
        result.addProperty(ProtocolElements.PUBLISHVIDEO_CREATEDAT_PARAM, createdAt);
        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, result);

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_USER_PARAM, participant.getParticipantPublicId());
        JsonObject stream = new JsonObject();

        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_STREAMID_PARAM, streamId);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_CREATEDAT_PARAM, createdAt);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_HASAUDIO_PARAM, mediaOptions.hasAudio);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_HASVIDEO_PARAM, mediaOptions.hasVideo);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_AUDIOACTIVE_PARAM, mediaOptions.audioActive);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_VIDEOACTIVE_PARAM, mediaOptions.videoActive);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_TYPEOFVIDEO_PARAM, mediaOptions.typeOfVideo);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_FRAMERATE_PARAM, mediaOptions.frameRate);
        stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_VIDEODIMENSIONS_PARAM, mediaOptions.videoDimensions);
        JsonElement filter = mediaOptions.getFilter() != null ? mediaOptions.getFilter().toJson() : new JsonObject();
        stream.add(ProtocolElements.JOINROOM_PEERSTREAMFILTER_PARAM, filter);

        JsonArray streamsArray = new JsonArray();
        streamsArray.add(stream);
        params.add(ProtocolElements.PARTICIPANTPUBLISHED_STREAMS_PARAM, streamsArray);

        for (Participant p : participants) {
            if (p.getParticipantPrivatetId().equals(participant.getParticipantPrivatetId())) {
                continue;
            } else {
                rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                        ProtocolElements.PARTICIPANTPUBLISHED_METHOD, params);
            }
        }
    }

    public void onUnpublishMedia(Participant participant, Set<Participant> participants, Participant moderator,
                                 Integer transactionId, CloudMediaException error, EndReason reason) {
        boolean isRpcFromModerator = transactionId != null && moderator != null;
        boolean isRpcFromOwner = transactionId != null && moderator == null;

        if (isRpcFromModerator) {
            if (error != null) {
                rpcNotificationService.sendErrorResponse(moderator.getParticipantPrivatetId(), transactionId, null,
                        error);
                return;
            }
            rpcNotificationService.sendResponse(moderator.getParticipantPrivatetId(), transactionId, new JsonObject());
        }

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.PARTICIPANTUNPUBLISHED_NAME_PARAM, participant.getParticipantPublicId());
        params.addProperty(ProtocolElements.PARTICIPANTUNPUBLISHED_REASON_PARAM, reason != null ? reason.name() : "");

        for (Participant p : participants) {
            if (p.getParticipantPrivatetId().equals(participant.getParticipantPrivatetId())) {
                // Send response to the affected participant
                if (!isRpcFromOwner) {
                    rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                            ProtocolElements.PARTICIPANTUNPUBLISHED_METHOD, params);
                } else {
                    if (error != null) {
                        rpcNotificationService.sendErrorResponse(p.getParticipantPrivatetId(), transactionId, null,
                                error);
                        return;
                    }
                    rpcNotificationService.sendResponse(p.getParticipantPrivatetId(), transactionId, new JsonObject());
                }
            } else {
                if (error == null) {
                    // Send response to every other user in the session different than the affected
                    // participant
                    rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                            ProtocolElements.PARTICIPANTUNPUBLISHED_METHOD, params);
                }
            }
        }
    }

    public void onSubscribe(Participant participant, MediaSession session, String sdpAnswer, Integer transactionId,
                            CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }
        JsonObject result = new JsonObject();
        //add by jeffrey
        result.addProperty("method", ProtocolElements.RECEIVEVIDEO_METHOD);
        result.addProperty(ProtocolElements.RECEIVEVIDEO_SDPANSWER_PARAM, sdpAnswer);
        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, result);
        //modify by jeffrey
       /* if (ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(participant.getParticipantPublicId())) {
            lock.lock();
            try {
                RecordingInfo recording = this.recordingsStarted.remove(session.getSessionId());
                if (recording != null) {
                    // RECORDER participant is now receiving video from the first publisher
                    this.sendRecordingStartedNotification(session, recording);
                }
            } finally {
                lock.unlock();
            }
        }*/
    }

    public void onUnsubscribe(Participant participant, Integer transactionId, CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }
        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, new JsonObject());
    }

    public void onSendMessage(Participant participant, JsonObject message, Set<Participant> participants,
                              Integer transactionId, CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_DATA_PARAM, message.get("data").getAsString());
        params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_FROM_PARAM, participant.getParticipantPublicId());
        params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_TYPE_PARAM, message.get("type").getAsString());

        Set<String> toSet = new HashSet<String>();

        if (message.has("to")) {
            JsonArray toJson = message.get("to").getAsJsonArray();
            for (int i = 0; i < toJson.size(); i++) {
                JsonElement el = toJson.get(i);
                if (el.isJsonNull()) {
                    throw new CloudMediaException(Code.SIGNAL_TO_INVALID_ERROR_CODE,
                            "Signal \"to\" field invalid format: null");
                }
                toSet.add(el.getAsString());
            }
        }

        if (toSet.isEmpty()) {
            for (Participant p : participants) {
                rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                        ProtocolElements.PARTICIPANTSENDMESSAGE_METHOD, params);
            }
        } else {
            Set<String> participantPublicIds = participants.stream().map(Participant::getParticipantPublicId)
                    .collect(Collectors.toSet());
            for (String to : toSet) {
                if (participantPublicIds.contains(to)) {
                    Optional<Participant> p = participants.stream().filter(x -> to.equals(x.getParticipantPublicId()))
                            .findFirst();
                    rpcNotificationService.sendNotification(p.get().getParticipantPrivatetId(),
                            ProtocolElements.PARTICIPANTSENDMESSAGE_METHOD, params);
                } else {
                    throw new CloudMediaException(Code.SIGNAL_TO_INVALID_ERROR_CODE,
                            "Signal \"to\" field invalid format: Connection [" + to + "] does not exist");
                }
            }
        }

        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, new JsonObject());
    }

    public void onStreamPropertyChanged(Participant participant, Integer transactionId, Set<Participant> participants,
                                        String streamId, String property, JsonElement newValue, String reason) {

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_CONNECTIONID_PARAM,
                participant.getParticipantPublicId());
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_STREAMID_PARAM, streamId);
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_PROPERTY_PARAM, property);
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_NEWVALUE_PARAM, newValue.toString());
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_REASON_PARAM, reason);

        for (Participant p : participants) {
            if (p.getParticipantPrivatetId().equals(participant.getParticipantPrivatetId())) {
                rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId,
                        new JsonObject());
            } else {
                rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                        ProtocolElements.STREAMPROPERTYCHANGED_METHOD, params);
            }
        }
    }

    public void onRecvIceCandidate(Participant participant, Integer transactionId, CloudMediaException error) {
        if (error != null) {
            rpcNotificationService.sendErrorResponse(participant.getParticipantPrivatetId(), transactionId, null, error);
            return;
        }
        rpcNotificationService.sendResponse(participant.getParticipantPrivatetId(), transactionId, new JsonObject());
    }

    public void onForceDisconnect(Participant moderator, Participant evictedParticipant, Set<Participant> participants,
                                  Integer transactionId, CloudMediaException error, EndReason reason) {

        boolean isRpcCall = transactionId != null;
        if (isRpcCall) {
            if (error != null) {
                rpcNotificationService.sendErrorResponse(moderator.getParticipantPrivatetId(), transactionId, null,
                        error);
                return;
            }
            rpcNotificationService.sendResponse(moderator.getParticipantPrivatetId(), transactionId, new JsonObject());
        }

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.PARTICIPANTEVICTED_CONNECTIONID_PARAM,
                evictedParticipant.getParticipantPublicId());
        params.addProperty(ProtocolElements.PARTICIPANTEVICTED_REASON_PARAM, reason != null ? reason.name() : "");

        if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(evictedParticipant.getParticipantPublicId())) {
            // Do not send a message when evicting RECORDER participant
            rpcNotificationService.sendNotification(evictedParticipant.getParticipantPrivatetId(),
                    ProtocolElements.PARTICIPANTEVICTED_METHOD, params);
        }
        for (Participant p : participants) {
            if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(evictedParticipant.getParticipantPublicId())) {
                rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                        ProtocolElements.PARTICIPANTEVICTED_METHOD, params);
            }
        }
    }

    public void sendRecordingStartedNotification(MediaSession session, RecordingInfo recordingInfo) {
        //modify by jeffrey
        //CDR.recordRecordingStarted(session.getSessionId(), recordingInfo);

        // Filter participants by roles according to "openvidu.recordingInfo.notification"
        Set<Participant> filteredParticipants = this.filterParticipantsByRole(
                this.cloudMediaConfig.getRolesFromRecordingNotification(), session.getParticipants());

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.RECORDINGSTARTED_ID_PARAM, recordingInfo.getId());
        params.addProperty(ProtocolElements.RECORDINGSTARTED_NAME_PARAM, recordingInfo.getName());

        for (Participant p : filteredParticipants) {
            rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                    ProtocolElements.RECORDINGSTARTED_METHOD, params);
        }
    }

    public void sendRecordingStoppedNotification(MediaSession session, RecordingInfo recordingInfo, EndReason reason) {
        //modify by jeffrey
        //CDR.recordRecordingStopped(session.getSessionId(), recordingInfo, reason);

        // Be sure to clean this map (this should return null)
        this.recordingsStarted.remove(session.getSessionId());

        // Filter participants by roles according to "openvidu.recordingInfo.notification"
        Set<Participant> existingParticipants;
        try {
            existingParticipants = session.getParticipants();
        } catch (CloudMediaException exception) {
            // Session is already closed. This happens when RecordingMode.ALWAYS and last
            // participant has left the session. No notification needs to be sent
            log.warn("Session already closed when trying to send 'recordingStopped' notification");
            return;
        }
        Set<Participant> filteredParticipants = this.filterParticipantsByRole(
                this.cloudMediaConfig.getRolesFromRecordingNotification(), existingParticipants);

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.RECORDINGSTOPPED_ID_PARAM, recordingInfo.getId());
        params.addProperty(ProtocolElements.RECORDINGSTARTED_NAME_PARAM, recordingInfo.getName());
        params.addProperty(ProtocolElements.RECORDINGSTOPPED_REASON_PARAM, reason != null ? reason.name() : "");

        for (Participant p : filteredParticipants) {
            rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                    ProtocolElements.RECORDINGSTOPPED_METHOD, params);
        }
    }

    public void onFilterChanged(Participant participant, Participant moderator, Integer transactionId,
                                Set<Participant> participants, String streamId, KurentoFilter filter, CloudMediaException error,
                                String filterReason) {
        boolean isRpcFromModerator = transactionId != null && moderator != null;

        if (isRpcFromModerator) {
            // A moderator forced the application of the filter
            if (error != null) {
                rpcNotificationService.sendErrorResponse(moderator.getParticipantPrivatetId(), transactionId, null,
                        error);
                return;
            }
            rpcNotificationService.sendResponse(moderator.getParticipantPrivatetId(), transactionId, new JsonObject());
        }

        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_CONNECTIONID_PARAM,
                participant.getParticipantPublicId());
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_STREAMID_PARAM, streamId);
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_PROPERTY_PARAM, "filter");
        JsonObject filterJson = new JsonObject();
        if (filter != null) {
            filterJson.addProperty(ProtocolElements.FILTER_TYPE_PARAM, filter.getType());
            filterJson.add(ProtocolElements.FILTER_OPTIONS_PARAM, filter.getOptions());
            if (filter.getLastExecMethod() != null) {
                filterJson.add(ProtocolElements.EXECFILTERMETHOD_LASTEXECMETHOD_PARAM,
                        filter.getLastExecMethod().toJson());
            }
        }
        params.add(ProtocolElements.STREAMPROPERTYCHANGED_NEWVALUE_PARAM, filterJson);
        params.addProperty(ProtocolElements.STREAMPROPERTYCHANGED_REASON_PARAM, filterReason);

        for (Participant p : participants) {
            if (p.getParticipantPrivatetId().equals(participant.getParticipantPrivatetId())) {
                // Affected participant
                if (isRpcFromModerator) {
                    // Force by moderator. Send notification to affected participant
                    rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                            ProtocolElements.STREAMPROPERTYCHANGED_METHOD, params);
                } else {
                    // Send response to participant
                    if (error != null) {
                        rpcNotificationService.sendErrorResponse(p.getParticipantPrivatetId(), transactionId, null,
                                error);
                        return;
                    }
                    rpcNotificationService.sendResponse(p.getParticipantPrivatetId(), transactionId, new JsonObject());
                }
            } else {
                // Send response to every other user in the session different than the affected
                // participant or the moderator
                if (error == null && (moderator == null
                        || !p.getParticipantPrivatetId().equals(moderator.getParticipantPrivatetId()))) {
                    rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                            ProtocolElements.STREAMPROPERTYCHANGED_METHOD, params);
                }
            }
        }
    }

    public void onFilterEventDispatched(String connectionId, String streamId, String filterType, String eventType,
                                        Object data, Set<Participant> participants, Set<String> subscribedParticipants) {
        JsonObject params = new JsonObject();
        params.addProperty(ProtocolElements.FILTEREVENTLISTENER_CONNECTIONID_PARAM, connectionId);
        params.addProperty(ProtocolElements.FILTEREVENTLISTENER_STREAMID_PARAM, streamId);
        params.addProperty(ProtocolElements.FILTEREVENTLISTENER_FILTERTYPE_PARAM, filterType);
        params.addProperty(ProtocolElements.FILTEREVENTLISTENER_EVENTTYPE_PARAM, eventType);
        params.addProperty(ProtocolElements.FILTEREVENTLISTENER_DATA_PARAM, data.toString());
        for (Participant p : participants) {
            if (subscribedParticipants.contains(p.getParticipantPublicId())) {
                rpcNotificationService.sendNotification(p.getParticipantPrivatetId(),
                        ProtocolElements.FILTEREVENTDISPATCHED_METHOD, params);
            }
        }
    }

    public void closeRpcSession(String participantPrivateId) {
        this.rpcNotificationService.closeRpcSession(participantPrivateId);
    }
    //modify by jeffrey
    public void setRecordingStarted(String sessionId, RecordingInfo recordingInfo) {
        this.recordingsStarted.put(sessionId, recordingInfo);
    }

    private Set<Participant> filterParticipantsByRole(CloudMediaRole[] roles, Set<Participant> participants) {
        return participants.stream().filter(part -> {
            if (ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(part.getParticipantPublicId())) {
                return false;
            }
            boolean isRole = false;
            for (CloudMediaRole role : roles) {
                isRole = role.equals(part.getToken().getRole());
                if (isRole)
                    break;
            }
            return isRole;
        }).collect(Collectors.toSet());
    }

}
