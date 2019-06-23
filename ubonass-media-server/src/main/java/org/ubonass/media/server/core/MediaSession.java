/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.ubonass.media.server.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.java.client.RecordingLayout;
import org.ubonass.media.java.client.SessionProperties;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.recording.service.RecordingManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class MediaSession {

    protected CloudMediaConfig cloudMediaConfig;

    protected RecordingManager recordingManager;
    /**
     * 私有ID
     * @Key:participantPrivatetId
     * @Value:KurentoParticipant
     */
    protected final ConcurrentMap<String, Participant> participants = new ConcurrentHashMap<>();
    protected String sessionId;
    protected SessionProperties sessionProperties;
    protected Long startTime;

    protected volatile boolean closed = false;
    protected AtomicInteger activePublishers = new AtomicInteger(0);

    public final AtomicBoolean recordingManuallyStopped = new AtomicBoolean(false);

    public MediaSession(MediaSession previousSession) {
        this.sessionId = previousSession.getSessionId();
        this.startTime = previousSession.getStartTime();
        this.sessionProperties = previousSession.getSessionProperties();
        this.cloudMediaConfig = previousSession.cloudMediaConfig;
        this.recordingManager = previousSession.recordingManager;
    }

    public MediaSession(String sessionId,
                        SessionProperties sessionProperties,
                        CloudMediaConfig cloudMediaConfig,
                        RecordingManager recordingManager) {
        this.sessionId = sessionId;
        this.startTime = System.currentTimeMillis();
        this.sessionProperties = sessionProperties;
        this.cloudMediaConfig = cloudMediaConfig;
        this.recordingManager = recordingManager;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public SessionProperties getSessionProperties() {
        return this.sessionProperties;
    }

    public Long getStartTime() {
        return this.startTime;
    }

    public Set<Participant> getParticipants() {
        checkClosed();
        return new HashSet<Participant>(this.participants.values());
    }

    public Participant getParticipantByPrivateId(String participantPrivateId) {
        checkClosed();
        return participants.get(participantPrivateId);
    }

    public Participant getParticipantByPublicId(String participantPublicId) {
        checkClosed();
        for (Participant p : participants.values()) {
            if (p.getParticipantPublicId().equals(participantPublicId)) {
                return p;
            }
        }
        return null;
    }

    public int getActivePublishers() {
        return activePublishers.get();
    }

    public void registerPublisher() {
        this.activePublishers.incrementAndGet();
    }

    public void deregisterPublisher() {
        this.activePublishers.decrementAndGet();
    }

    public boolean isClosed() {
        return closed;
    }

    protected void checkClosed() {
        if (isClosed()) {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_CLOSED_ERROR_CODE, "The session '" + sessionId + "' is closed");
        }
    }

    public JsonObject toJson() {
        return this.sharedJson(KurentoParticipant::toJson);
    }

    public JsonObject withStatsToJson() {
        return this.sharedJson(KurentoParticipant::withStatsToJson);
    }

    private JsonObject sharedJson(Function<KurentoParticipant, JsonObject> toJsonFunction) {
        JsonObject json = new JsonObject();
        json.addProperty("sessionId", this.sessionId);
        json.addProperty("createdAt", this.startTime);
        json.addProperty("mediaMode", this.sessionProperties.mediaMode().name());
        json.addProperty("recordingMode", this.sessionProperties.recordingMode().name());
        json.addProperty("defaultOutputMode", this.sessionProperties.defaultOutputMode().name());
        if (RecordingInfo.OutputMode.COMPOSED.equals(this.sessionProperties.defaultOutputMode())) {
            json.addProperty("defaultRecordingLayout", this.sessionProperties.defaultRecordingLayout().name());
            if (RecordingLayout.CUSTOM.equals(this.sessionProperties.defaultRecordingLayout())) {
                json.addProperty("defaultCustomLayout", this.sessionProperties.defaultCustomLayout());
            }
        }
        if (this.sessionProperties.customSessionId() != null) {
            json.addProperty("customSessionId", this.sessionProperties.customSessionId());
        }
        JsonObject connections = new JsonObject();
        JsonArray participants = new JsonArray();
        this.participants.values().forEach(p -> {
            if (!ProtocolElements.RECORDER_PARTICIPANT_PUBLICID.equals(p.getParticipantPublicId())) {
                participants.add(toJsonFunction.apply((KurentoParticipant) p));
            }
        });
        connections.addProperty("numberOfElements", participants.size());
        connections.add("content", participants);
        json.add("connections", connections);
        json.addProperty("recording", this.recordingManager.sessionIsBeingRecorded(this.sessionId));
        return json;
    }


	public void join(Participant participant,boolean remoteNeed) { }


	public void leave(String participantPrivateId, EndReason reason) { }


	public boolean close(EndReason reason) { return false; }

}
