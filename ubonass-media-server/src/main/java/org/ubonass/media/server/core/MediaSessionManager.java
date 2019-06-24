package org.ubonass.media.server.core;

import com.google.gson.JsonObject;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.cluster.ClusterSessionEvent;
import org.ubonass.media.server.cluster.ClusterSessionManager;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.recording.service.RecordingManager;
import org.ubonass.media.server.utils.FormatChecker;

import javax.annotation.PreDestroy;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public abstract class MediaSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(MediaSessionManager.class);

    @Autowired
    protected CloudMediaConfig cloudMediaConfig;

    @Autowired
    protected SessionEventsHandler sessionEventsHandler;

    @Autowired
    protected ClusterRpcService clusterRpcService;

    @Autowired
    protected ClusterSessionManager clusterSessionManager;

    @Autowired
    protected ClusterSessionEvent clusterSessionEvent;

    @Autowired
    protected RecordingManager recordingManager;

    public FormatChecker formatChecker = new FormatChecker();


    /**
     * @Key: media sessionId
     * @Value:KurentoSession 为MediaSession的子类
     * 管理所有的房间
     */
    protected ConcurrentMap<String, MediaSession> sessions = new ConcurrentHashMap<>();
    /**
     * @Key: media sessionId
     * @Value:MediaSession 未使用的
     * 管理所有的空房间
     */
    protected ConcurrentMap<String, MediaSession> sessionsNotActive = new ConcurrentHashMap<>();

    /**
     * @key：mediasession ID
     * @Value:ConcurrentHashMap<String, Participant> 该房间有哪些参与着
     * 客户端participantPublicId 客户端唯一ID号
     * 客户端对应的Participant
     */
    protected ConcurrentMap<String, ConcurrentHashMap<String, Participant>> sessionidParticipantpublicidParticipant = new ConcurrentHashMap<>();
    /**
     * @Key:房间名称
     * @Value:存储Token的集合
     * @key:tokenString
     * @value:Token
     */
    public ConcurrentMap<String, ConcurrentHashMap<String, Token>> sessionidTokenTokenobj = new ConcurrentHashMap<>();

    private volatile boolean closed = false;


    public Participant newCallParticipant(String memberId,
                                          String sessionId,
                                          String participantPrivatetId,
                                          String participantPublicId) {
        /*if (!sessionidParticipantpublicidParticipant
                .containsKey(sessionId)) {
            this.sessionidParticipantpublicidParticipant
                    .putIfAbsent(sessionId, new ConcurrentHashMap<>());
        }
        if (this.sessionidParticipantpublicidParticipant.get(sessionId) != null) {
            Participant p = new Participant(participantPrivatetId,
                    participantPublicId, sessionId, null, null, null, null, null);
            this.sessionidParticipantpublicidParticipant.get(sessionId).putIfAbsent(participantPublicId, p);
            return p;
        } else {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, sessionId);
        }*/
        Participant p = new Participant(memberId, participantPrivatetId,
                participantPublicId, sessionId, null, null, null, null, null);
        return p;

    }

    public boolean sessionExist(String sessionId) {

        return sessions.containsKey(sessionId);
    }

    /**
     * Returns a Session given its id
     *
     * @return Session
     */
    public MediaSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Returns all currently active (opened) sessions.
     *
     * @return set of the session's identifiers
     */
    public Collection<MediaSession> getSessions() {
        return sessions.values();
    }

    public MediaSession getSessionNotActive(String sessionId) {
        return this.sessionsNotActive.get(sessionId);
    }

    public Collection<MediaSession> getSessionsWithNotActive() {
        Collection<MediaSession> allSessions = new HashSet<>();
        allSessions.addAll(this.sessionsNotActive.values().stream()
                .filter(sessionNotActive -> !sessions.containsKey(sessionNotActive.getSessionId()))
                .collect(Collectors.toSet()));
        allSessions.addAll(this.getSessions());
        return allSessions;
    }

    /**
     * Returns all the participants inside a session.
     *
     * @param sessionId identifier of the session
     * @return set of {@link Participant}
     * @throws CloudMediaException in case the session doesn't exist
     */
    public Set<Participant> getParticipants(String sessionId) throws CloudMediaException {
        MediaSession session = sessions.get(sessionId);
        if (session == null) {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId + "' not found");
        }
        Set<Participant> participants = session.getParticipants();
        participants.removeIf(p -> p.isClosed());
        return participants;
    }

    /**
     * Returns a participant in a session
     *
     * @param sessionId            identifier of the session
     * @param participantPrivateId private identifier of the participant
     * @return {@link Participant}
     * @throws CloudMediaException in case the session doesn't exist or the
     *                             participant doesn't belong to it
     */
    public Participant getParticipant(String sessionId, String participantPrivateId) throws CloudMediaException {
        MediaSession session = sessions.get(sessionId);
        if (session == null) {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId + "' not found");
        }
        Participant participant = session.getParticipantByPrivateId(participantPrivateId);
        if (participant == null) {
            throw new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE,
                    "Participant '" + participantPrivateId + "' not found in session '" + sessionId + "'");
        }
        return participant;
    }

    /**
     * Returns a participant
     *
     * @param participantPrivateId private identifier of the participant
     * @return {@link Participant}
     * @throws CloudMediaException in case the participant doesn't exist
     */
    public Participant getParticipant(String participantPrivateId) throws CloudMediaException {
        for (MediaSession session : sessions.values()) {
            if (!session.isClosed()) {
                if (session.getParticipantByPrivateId(participantPrivateId) != null) {
                    return session.getParticipantByPrivateId(participantPrivateId);
                }
            }
        }
        throw new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE,
                "No participant with private id '" + participantPrivateId + "' was found");
    }

    public void showTokens() {
        logger.info("<SESSIONID, TOKENS>: {}", this.sessionidTokenTokenobj.toString());
    }

    /**
     * Closes all resources. This method has been annotated with the @PreDestroy
     * directive (javax.annotation package) so that it will be automatically called
     * when the MediaSessionManager instance is container-managed. <br/>
     * <strong>Dev advice:</strong> Send notifications to all participants to inform
     * that their session has been forcibly closed.
     *
     * //@see MediaSessionManmager#closeSession(String)
     */
    @PreDestroy
    public void close() {
        closed = true;
        logger.info("Closing all sessions");
        for (String sessionId : sessions.keySet()) {
            try {
                closeSession(sessionId, EndReason.openviduServerStopped);
            } catch (Exception e) {
                logger.warn("Error closing session '{}'", sessionId, e);
            }
        }
    }

    /**
     * Closes an existing session by releasing all resources that were allocated for
     * it. Once closed, the session can be reopened (will be empty and it will use
     * another Media Pipeline). Existing participants will be evicted. <br/>
     * <strong>Dev advice:</strong> The session event handler should send
     * notifications to the existing participants in the session to inform that it
     * was forcibly closed.
     *
     * @param sessionId identifier of the session
     * @return set of {@link Participant} POJOS representing the session's
     * participants
     * @throws CloudMediaException in case the session doesn't exist or has been
     *                             already closed
     */
    public Set<Participant> closeSession(String sessionId, EndReason reason) {
        MediaSession session = sessions.get(sessionId);
        if (session == null) {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId + "' not found");
        }
        if (session.isClosed()) {
            this.closeSessionAndEmptyCollections(session, reason);
            throw new CloudMediaException(Code.ROOM_CLOSED_ERROR_CODE, "Session '" + sessionId + "' already closed");
        }
        Set<Participant> participants = getParticipants(sessionId);
        for (Participant p : participants) {
            try {
                this.evictParticipant(p, null, null, reason);
            } catch (CloudMediaException e) {
                logger.warn("Error evicting participant '{}' from session '{}'", p.getParticipantPublicId(), sessionId, e);
            }
        }

        this.closeSessionAndEmptyCollections(session, reason);

        return participants;
    }

    public void closeSessionAndEmptyCollections(MediaSession session, EndReason reason) {

        /*if (cloudMediaConfig.isRecordingModuleEnabled()
                && this.recordingManager.sessionIsBeingRecorded(session.getSessionId())) {
            recordingManager.stopRecording(session, null, RecordingManager.finalReason(reason));
        }*/

        if (session.close(reason)) {
            sessionEventsHandler.onSessionClosed(session.getSessionId(), reason);
        }
        if (sessions.containsKey(session.getSessionId()))
            sessions.remove(session.getSessionId());
        if (sessionsNotActive.containsKey(session.getSessionId()))
            sessionsNotActive.remove(session.getSessionId());
        if (sessionidParticipantpublicidParticipant.containsKey(session.getSessionId()))
            sessionidParticipantpublicidParticipant.remove(session.getSessionId());
        /*sessionidFinalUsers.remove(session.getSessionId());
        sessionidAccumulatedRecordings.remove(session.getSessionId());
        sessionidTokenTokenobj.remove(session.getSessionId());*/
        /**
         * 将集群中的session删除
         * add by jeffrey
         */
        if (cloudMediaConfig.isSessionClusterEnable())
            clusterSessionManager.closeSession(session.getSessionId());

        logger.info("Session '{}' removed and closed", session.getSessionId());
    }

    public abstract void call(Participant participant, String calleeId, MediaOptions mediaOptions, Integer transactionId);

    public abstract void onCallAccept(Participant participant, String callerId, MediaOptions mediaOptions, Integer transactionId);

    public abstract void onCallReject(String sessionId, String callerId, Integer transactionId);

    public abstract void onCallHangup(Participant participant, Integer transactionId);

    public abstract void joinRoom(Participant participant, String sessionId, Integer transactionId);

    public abstract void subscribe(Participant participant, String senderName, String sdpOffer, Integer transactionId);

    public abstract void onIceCandidate(Participant participant, String endpointName, String candidate,
                                        int sdpMLineIndex, String sdpMid, Integer transactionId);

    public abstract void leaveRoom(Participant participant, Integer transactionId, EndReason reason,
                                   boolean closeWebSocket);

    public abstract void evictParticipant(Participant evictedParticipant, Participant moderator, Integer transactionId,
                                          EndReason reason);

    public MediaOptions generateMediaOptions(Request<JsonObject> request) {
        return null;
    }
}
