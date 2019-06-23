

package org.ubonass.media.server.recording.service;

import org.kurento.client.MediaProfileSpecType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.java.client.RecordingLayout;
import org.ubonass.media.java.client.RecordingProperties;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.EndReason;
import org.ubonass.media.server.core.MediaSession;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.utils.CommandExecutor;
import org.ubonass.media.server.utils.CustomFileManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public abstract class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    protected CloudMediaConfig cloudMediaConfig;
    protected RecordingManager recordingManager;
    protected CustomFileManager fileWriter = new CustomFileManager();

    RecordingService(RecordingManager recordingManager, CloudMediaConfig cloudMediaConfig) {
        this.recordingManager = recordingManager;
        this.cloudMediaConfig = cloudMediaConfig;
    }

    public abstract RecordingInfo startRecording(MediaSession session, RecordingProperties properties) throws CloudMediaException;

    public abstract RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason);

    public abstract RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason, boolean forceAfterKmsRestart);

    /**
     * SingleStreamRecordingService子类重写
     * @param session
     * @param recordingId
     * @param profile
     * @param participant
     * @param globalStartLatch
     */
    public void startRecorderEndpointForPublisherEndpoint(MediaSession session, String recordingId,
                                                          MediaProfileSpecType profile, Participant participant, CountDownLatch globalStartLatch){}

    /**
     * SingleStreamRecordingService子类重写
     * @param sessionId
     * @param streamId
     * @param globalStopLatch
     * @param forceAfterKmsRestart
     */
    public void stopRecorderEndpointOfPublisherEndpoint(String sessionId, String streamId,
                                                        CountDownLatch globalStopLatch, boolean forceAfterKmsRestart){}

    /**
     *ComposedRecordingService子类重写
     * @param session
     * @param recordingId
     * @param participant
     */
    public void joinPublisherEndpointToComposite(MediaSession session, String recordingId, Participant participant){}

    /**
     * ComposedRecordingService子类重写
     * @param sessionId
     * @param streamId
     */
    public void removePublisherEndpointFromComposite(String sessionId, String streamId){}
    /**
     * Generates metadata recordingInfo file (".recordingInfo.RECORDING_ID" JSON file to
     * store RecordingInfo entity)
     */
    protected void generateRecordingMetadataFile(RecordingInfo recordingInfo) {
        String folder = this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId();
        boolean newFolderCreated = this.fileWriter.createFolderIfNotExists(folder);

        if (newFolderCreated) {
            log.info(
                    "New folder {} created. This means the recordingInfo started for a session with no publishers or no media type compatible publishers",
                    folder);
        } else {
            log.info("Folder {} already existed. Some publisher is already being recorded", folder);
        }

        String filePath = this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId() + "/"
                + RecordingManager.RECORDING_ENTITY_FILE + recordingInfo.getId();
        String text = recordingInfo.toJson().toString();
        this.fileWriter.createAndWriteFile(filePath, text);
        log.info("Generated recordingInfo metadata file at {}", filePath);
    }

    /**
     * Update and overwrites metadata recordingInfo file with final values on recordingInfo
     * stop (".recordingInfo.RECORDING_ID" JSON file to store RecordingInfo entity).
     *
     * @return updated RecordingInfo object
     */
    protected RecordingInfo sealRecordingMetadataFile(RecordingInfo recordingInfo, long size, double duration,
                                                      String metadataFilePath) {
        recordingInfo.setSize(size); // Size in bytes
        recordingInfo.setDuration(duration > 0 ? duration : 0); // Duration in seconds
        if (!RecordingInfo.Status.failed.equals(recordingInfo.getStatus())) {
            recordingInfo.setStatus(RecordingInfo.Status.stopped);
        }
        this.fileWriter.overwriteFile(metadataFilePath, recordingInfo.toJson().toString());
        recordingInfo = this.recordingManager.updateRecordingUrl(recordingInfo);

        log.info("Sealed recordingInfo metadata file at {}", metadataFilePath);

        return recordingInfo;
    }

    /**
     * Changes recordingInfo from starting to started, updates global recordingInfo
     * collections and sends RPC response to clients
     */
    protected void updateRecordingManagerCollections(MediaSession session, RecordingInfo recordingInfo) {
        this.recordingManager.sessionHandler.setRecordingStarted(session.getSessionId(), recordingInfo);
        this.recordingManager.sessionsRecordings.put(session.getSessionId(), recordingInfo);
        this.recordingManager.startingRecordings.remove(recordingInfo.getId());
        this.recordingManager.startedRecordings.put(recordingInfo.getId(), recordingInfo);
    }

    /**
     * Sends RPC response for recordingInfo started event
     */
    protected void sendRecordingStartedNotification(MediaSession session, RecordingInfo recordingInfo) {
        this.recordingManager.getSessionEventsHandler().sendRecordingStartedNotification(session, recordingInfo);
    }

    /**
     * Returns a new available recording identifier (adding a number tag at the end
     * of the sessionId if it already exists) and rebuilds RecordinProperties object
     * to set the final value of "name" property
     */
    protected PropertiesRecordingId setFinalRecordingNameAndGetFreeRecordingId(MediaSession session,
                                                                               RecordingProperties properties) {
        String recordingId = this.recordingManager.getFreeRecordingId(session.getSessionId(),
                this.getShortSessionId(session));
        if (properties.name() == null || properties.name().isEmpty()) {
            // No name provided for the recording file. Use recordingId
            RecordingProperties.Builder builder = new RecordingProperties.Builder().name(recordingId)
                    .outputMode(properties.outputMode()).hasAudio(properties.hasAudio())
                    .hasVideo(properties.hasVideo());
            if (RecordingInfo.OutputMode.COMPOSED.equals(properties.outputMode())
                    && properties.hasVideo()) {
                builder.resolution(properties.resolution());
                builder.recordingLayout(properties.recordingLayout());
                if (RecordingLayout.CUSTOM.equals(properties.recordingLayout())) {
                    builder.customLayout(properties.customLayout());
                }
            }
            properties = builder.build();
        }

        log.info("New recording id ({}) and final name ({})", recordingId, properties.name());
        return new PropertiesRecordingId(properties, recordingId);
    }

    protected void updateFilePermissions(String folder) {
        String command = "chmod -R 777 " + folder;
        try {
            String response = CommandExecutor.execCommand("/bin/sh", "-c", command);
            if ("".equals(response)) {
                log.info("Individual recording file permissions successfully updated");
            } else {
                log.error("Individual recording file permissions failed to update: {}", response);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Individual recording file permissions failed to update. Error: {}", e.getMessage());
        }
    }

    protected String getShortSessionId(MediaSession session) {
        return session.getSessionId().substring(session.getSessionId().lastIndexOf('/') + 1,
                session.getSessionId().length());
    }

    protected CloudMediaException failStartRecording(MediaSession session, RecordingInfo recordingInfo, String errorMessage) {
        log.error("RecordingInfo start failed for session {}: {}", session.getSessionId(), errorMessage);
        recordingInfo.setStatus(RecordingInfo.Status.failed);
        this.recordingManager.startingRecordings.remove(recordingInfo.getId());
        this.stopRecording(session, recordingInfo, null);
        return new CloudMediaException(Code.RECORDING_START_ERROR_CODE, errorMessage);
    }

    protected void cleanRecordingMaps(RecordingInfo recordingInfo) {
        this.recordingManager.sessionsRecordings.remove(recordingInfo.getSessionId());
        this.recordingManager.startedRecordings.remove(recordingInfo.getId());
    }

    /**
     * Simple wrapper for returning update RecordingProperties and a free
     * recordingId when starting a new recording
     */
    protected class PropertiesRecordingId {

        RecordingProperties properties;
        String recordingId;

        PropertiesRecordingId(RecordingProperties properties, String recordingId) {
            this.properties = properties;
            this.recordingId = recordingId;
        }
    }

}
