

package org.ubonass.media.server.recording.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;
import org.kurento.client.EventListener;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.java.client.RecordingProperties;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.*;
import org.ubonass.media.server.kurento.CloudMediaKurentoClientSessionInfo;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.kurento.KurentoClientSessionInfo;
import org.ubonass.media.server.utils.CustomFileManager;
import org.ubonass.media.server.utils.DockerManager;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class RecordingManager {

    private static final Logger log = LoggerFactory.getLogger(RecordingManager.class);

    private RecordingService composedRecordingService;
    private RecordingService singleStreamRecordingService;

    private DockerManager dockerManager;

    @Autowired
    protected SessionEventsHandler sessionHandler;

    @Autowired
    private MediaSessionManager sessionManager;

    @Autowired
    protected CloudMediaConfig cloudMediaConfig;

    @Autowired
    private KurentoClientProvider kcProvider;

    protected Map<String, RecordingInfo> startingRecordings = new ConcurrentHashMap<>();
    protected Map<String, RecordingInfo> startedRecordings = new ConcurrentHashMap<>();
    protected Map<String, RecordingInfo> sessionsRecordings = new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> automaticRecordingStopThreads = new ConcurrentHashMap<>();

    private ScheduledThreadPoolExecutor automaticRecordingStopExecutor = new ScheduledThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors());

    static final String RECORDING_ENTITY_FILE = ".recording.";
    public static final String IMAGE_NAME = "openvidu/openvidu-recording";
    static String IMAGE_TAG;

    private static final List<EndReason> LAST_PARTICIPANT_LEFT_REASONS = Arrays
            .asList(new EndReason[]{EndReason.disconnect, EndReason.forceDisconnectByUser,
                    EndReason.forceDisconnectByServer, EndReason.networkDisconnect});

    public SessionEventsHandler getSessionEventsHandler() {
        return this.sessionHandler;
    }

    public MediaSessionManager getSessionManager() {
        return this.sessionManager;
    }

    /**
     *
     * @throws CloudMediaException
     */
    public void initializeRecordingManager() throws CloudMediaException {

        RecordingManager.IMAGE_TAG = cloudMediaConfig.getRecordingVersion();

        this.dockerManager = new DockerManager();
        this.composedRecordingService = new ComposedRecordingService(this, cloudMediaConfig);
        this.singleStreamRecordingService = new SingleStreamRecordingService(this, cloudMediaConfig);

        log.info("RecordingInfo module required: Downloading openvidu/openvidu-recording:"
                + cloudMediaConfig.getRecordingVersion() + " Docker image (350MB aprox)");

        this.checkRecordingRequirements(this.cloudMediaConfig.getRecordingPath(),
                this.cloudMediaConfig.getRecordingCustomLayout());

        if (dockerManager.dockerImageExistsLocally(IMAGE_NAME + ":" + IMAGE_TAG)) {
            log.info("Docker image already exists locally");
        } else {
            Thread t = new Thread(() -> {
                boolean keep = true;
                log.info("Downloading ");
                while (keep) {
                    System.out.print(".");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        keep = false;
                        log.info("\nDownload complete");
                    }
                }
            });
            t.start();
            try {
                dockerManager.downloadDockerImage(IMAGE_NAME + ":" + IMAGE_TAG, 600);
            } catch (Exception e) {
                log.error("Error downloading docker image {}:{}", IMAGE_NAME, IMAGE_TAG);
            }
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            log.info("Docker image available");
        }

        // Clean any stranded openvidu/openvidu-recording container on startup
        dockerManager.cleanStrandedContainers(RecordingManager.IMAGE_NAME);
    }

    public void checkRecordingRequirements(String recordingPath, String recordingCustomLayout)
            throws CloudMediaException {
        if (dockerManager == null) {
            this.dockerManager = new DockerManager();
        }
        dockerManager.checkDockerEnabled(cloudMediaConfig.getSpringProfile());
        this.checkRecordingPaths(recordingPath, recordingCustomLayout);
    }

    public RecordingInfo startRecording(MediaSession session, RecordingProperties properties) throws CloudMediaException {
        RecordingInfo recordingInfo = null;
        try {
            switch (properties.outputMode()) {
                case COMPOSED:
                    recordingInfo = this.composedRecordingService.startRecording(session, properties);
                    break;
                case INDIVIDUAL:
                    recordingInfo = this.singleStreamRecordingService.startRecording(session, properties);
                    break;
            }
        } catch (CloudMediaException e) {
            throw e;
        }
        if (session.getActivePublishers() == 0) {
            // Init automatic recordingInfo stop if there are now publishers when starting
            // recordingInfo
            log.info("No publisher in session {}. Starting {} seconds countdown for stopping recordingInfo",
                    session.getSessionId(), this.cloudMediaConfig.getRecordingAutostopTimeout());
            this.initAutomaticRecordingStopThread(session);
        }
        return recordingInfo;
    }

    public RecordingInfo stopRecording(MediaSession session, String recordingId, EndReason reason) {
        RecordingInfo recordingInfo;
        if (session == null) {
            recordingInfo = this.startedRecordings.get(recordingId);
        } else {
            recordingInfo = this.sessionsRecordings.get(session.getSessionId());
        }
        switch (recordingInfo.getOutputMode()) {
            case COMPOSED:
                recordingInfo = this.composedRecordingService.stopRecording(session, recordingInfo, reason);
                break;
            case INDIVIDUAL:
                recordingInfo = this.singleStreamRecordingService.stopRecording(session, recordingInfo, reason);
                break;
        }
        this.abortAutomaticRecordingStopThread(session);
        return recordingInfo;
    }

    public RecordingInfo forceStopRecording(MediaSession session, EndReason reason) {
        RecordingInfo recordingInfo;
        recordingInfo = this.sessionsRecordings.get(session.getSessionId());
        switch (recordingInfo.getOutputMode()) {
            case COMPOSED:
                recordingInfo = this.composedRecordingService.stopRecording(session, recordingInfo, reason, true);
                break;
            case INDIVIDUAL:
                recordingInfo = this.singleStreamRecordingService.stopRecording(session, recordingInfo, reason, true);
                break;
        }
        this.abortAutomaticRecordingStopThread(session);
        return recordingInfo;
    }

    public void startOneIndividualStreamRecording(MediaSession session, String recordingId, MediaProfileSpecType profile,
                                                  Participant participant) {
        RecordingInfo recordingInfo = this.sessionsRecordings.get(session.getSessionId());
        if (recordingInfo == null) {
            log.error("Cannot start recordingInfo of new stream {}. Session {} is not being recorded",
                    participant.getPublisherStreamId(), session.getSessionId());
        }
        if (RecordingInfo.OutputMode.INDIVIDUAL.equals(recordingInfo.getOutputMode())) {
            // Start new RecorderEndpoint for this stream
            log.info("Starting new RecorderEndpoint in session {} for new stream of participant {}",
                    session.getSessionId(), participant.getParticipantPublicId());
            final CountDownLatch startedCountDown = new CountDownLatch(1);
            this.singleStreamRecordingService.startRecorderEndpointForPublisherEndpoint(session, recordingId, profile,
                    participant, startedCountDown);
        } else if (RecordingInfo.OutputMode.COMPOSED.equals(recordingInfo.getOutputMode())
                && !recordingInfo.hasVideo()) {
            // Connect this stream to existing Composite recorder
            log.info("Joining PublisherEndpoint to existing Composite in session {} for new stream of participant {}",
                    session.getSessionId(), participant.getParticipantPublicId());
            this.composedRecordingService.joinPublisherEndpointToComposite(session, recordingId, participant);
        }
    }

    public void stopOneIndividualStreamRecording(String sessionId, String streamId, boolean forceAfterKmsRestart) {
        RecordingInfo recordingInfo = this.sessionsRecordings.get(sessionId);
        if (recordingInfo == null) {
            log.error("Cannot stop recordingInfo of existing stream {}. Session {} is not being recorded", streamId,
                    sessionId);
        }
        if (RecordingInfo.OutputMode.INDIVIDUAL.equals(recordingInfo.getOutputMode())) {
            // Stop specific RecorderEndpoint for this stream
            log.info("Stopping RecorderEndpoint in session {} for stream of participant {}", sessionId, streamId);
            final CountDownLatch stoppedCountDown = new CountDownLatch(1);
            this.singleStreamRecordingService.stopRecorderEndpointOfPublisherEndpoint(sessionId, streamId,
                    stoppedCountDown, forceAfterKmsRestart);
            try {
                if (!stoppedCountDown.await(5, TimeUnit.SECONDS)) {
                    log.error("Error waiting for recorder endpoint of stream {} to stop in session {}", streamId,
                            sessionId);
                }
            } catch (InterruptedException e) {
                log.error("Exception while waiting for state change", e);
            }
        } else if (RecordingInfo.OutputMode.COMPOSED.equals(recordingInfo.getOutputMode())
                && !recordingInfo.hasVideo()) {
            // Disconnect this stream from existing Composite recorder
            log.info("Removing PublisherEndpoint from Composite in session {} for stream of participant {}", sessionId,
                    streamId);
            this.composedRecordingService.removePublisherEndpointFromComposite(sessionId, streamId);
        }
    }

    public boolean sessionIsBeingRecorded(String sessionId) {
        return (this.sessionsRecordings.get(sessionId) != null);
    }

    public RecordingInfo getStartedRecording(String recordingId) {
        return this.startedRecordings.get(recordingId);
    }

    public RecordingInfo getStartingRecording(String recordingId) {
        return this.startingRecordings.get(recordingId);
    }

    public Collection<RecordingInfo> getFinishedRecordings() {
        return this.getAllRecordingsFromHost().stream()
                .filter(recording -> (recording.getStatus().equals(RecordingInfo.Status.stopped)
                        || recording.getStatus().equals(RecordingInfo.Status.available)))
                .collect(Collectors.toSet());
    }

    public RecordingInfo getRecording(String recordingId) {
        return this.getRecordingFromHost(recordingId);
    }

    public Collection<RecordingInfo> getAllRecordings() {
        return this.getAllRecordingsFromHost();
    }

    public String getFreeRecordingId(String sessionId, String shortSessionId) {
        Set<String> recordingIds = this.getRecordingIdsFromHost();
        String recordingId = shortSessionId;
        boolean isPresent = recordingIds.contains(recordingId);
        int i = 1;

        while (isPresent) {
            recordingId = shortSessionId + "-" + i;
            i++;
            isPresent = recordingIds.contains(recordingId);
        }

        return recordingId;
    }

    public HttpStatus deleteRecordingFromHost(String recordingId, boolean force) {

        if (!force && (this.startedRecordings.containsKey(recordingId)
                || this.startingRecordings.containsKey(recordingId))) {
            // Cannot delete an active recordingInfo
            return HttpStatus.CONFLICT;
        }

        RecordingInfo recordingInfo = getRecordingFromHost(recordingId);
        if (recordingInfo == null) {
            return HttpStatus.NOT_FOUND;
        }

        File folder = new File(this.cloudMediaConfig.getRecordingPath());
        File[] files = folder.listFiles();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory() && files[i].getName().equals(recordingId)) {
                // Correct folder. Delete it
                try {
                    FileUtils.deleteDirectory(files[i]);
                } catch (IOException e) {
                    log.error("Couldn't delete folder {}", files[i].getAbsolutePath());
                }
                break;
            }
        }

        return HttpStatus.NO_CONTENT;
    }

    public RecordingInfo getRecordingFromEntityFile(File file) {
        if (file.isFile() && file.getName().startsWith(RecordingManager.RECORDING_ENTITY_FILE)) {
            JsonObject json = null;
            FileReader fr = null;
            try {
                fr = new FileReader(file);
                json = new JsonParser().parse(fr).getAsJsonObject();
            } catch (IOException e) {
                return null;
            } finally {
                try {
                    fr.close();
                } catch (Exception e) {
                    log.error("Exception while closing FileReader: {}", e.getMessage());
                }
            }
            return new RecordingInfo(json);
        }
        return null;
    }

    public void initAutomaticRecordingStopThread(final MediaSession session) {
        final String recordingId = this.sessionsRecordings.get(session.getSessionId()).getId();
        ScheduledFuture<?> future = this.automaticRecordingStopExecutor.schedule(() -> {

            log.info("Stopping recording {} after {} seconds wait (no publisher published before timeout)", recordingId,
                    this.cloudMediaConfig.getRecordingAutostopTimeout());

            if (this.automaticRecordingStopThreads.remove(session.getSessionId()) != null) {
                if (session.getParticipants().size() == 0 || (session.getParticipants().size() == 1
                        && session.getParticipantByPublicId(ProtocolElements.RECORDER_PARTICIPANT_PUBLICID) != null)) {
                    // Close session if there are no participants connected (except for RECORDER).
                    // This code won't be executed only when some user reconnects to the session
                    // but never publishing (publishers automatically abort this thread)
                    log.info("Closing session {} after automatic stop of recording {}", session.getSessionId(),
                            recordingId);
                    sessionManager.closeSessionAndEmptyCollections(session, EndReason.automaticStop);
                    sessionManager.showTokens();
                } else {
                    this.stopRecording(session, recordingId, EndReason.automaticStop);
                }
            } else {
                // This code is reachable if there already was an automatic stop of a recording
                // caused by not user publishing within timeout after recording started, and a
                // new automatic stop thread was started by last user leaving the session
                log.warn("RecordingInfo {} was already automatically stopped by a previous thread", recordingId);
            }

        }, this.cloudMediaConfig.getRecordingAutostopTimeout(), TimeUnit.SECONDS);
        this.automaticRecordingStopThreads.putIfAbsent(session.getSessionId(), future);
    }

    public boolean abortAutomaticRecordingStopThread(MediaSession session) {
        ScheduledFuture<?> future = this.automaticRecordingStopThreads.remove(session.getSessionId());
        if (future != null) {
            boolean cancelled = future.cancel(false);
            if (session.getParticipants().size() == 0 || (session.getParticipants().size() == 1
                    && session.getParticipantByPublicId(ProtocolElements.RECORDER_PARTICIPANT_PUBLICID) != null)) {
                // Close session if there are no participants connected (except for RECORDER).
                // This code will only be executed if recording is manually stopped during the
                // automatic stop timeout, so the session must be also closed
                log.info(
                        "Ongoing recording of session {} was explicetly stopped within timeout for automatic recording stop. Closing session",
                        session.getSessionId());
                sessionManager.closeSessionAndEmptyCollections(session, EndReason.automaticStop);
                sessionManager.showTokens();
            }
            return cancelled;
        } else {
            return true;
        }
    }

    public RecordingInfo updateRecordingUrl(RecordingInfo recordingInfo) {
        if (cloudMediaConfig.isRecordingPublicAccess()) {
            if (RecordingInfo.Status.stopped.equals(recordingInfo.getStatus())) {

                String extension;
                switch (recordingInfo.getOutputMode()) {
                    case COMPOSED:
                        extension = recordingInfo.hasVideo() ? "mp4" : "webm";
                        break;
                    case INDIVIDUAL:
                        extension = "zip";
                        break;
                    default:
                        extension = "mp4";
                }

                recordingInfo.setUrl(this.cloudMediaConfig.getFinalUrl() + "recordings/" + recordingInfo.getId() + "/"
                        + recordingInfo.getName() + "." + extension);
                recordingInfo.setStatus(RecordingInfo.Status.available);
            }
        }
        return recordingInfo;
    }

    private RecordingInfo getRecordingFromHost(String recordingId) {
        log.info(this.cloudMediaConfig.getRecordingPath() + recordingId + "/"
                + RecordingManager.RECORDING_ENTITY_FILE + recordingId);
        File file = new File(this.cloudMediaConfig.getRecordingPath() + recordingId + "/"
                + RecordingManager.RECORDING_ENTITY_FILE + recordingId);
        log.info("File exists: " + file.exists());
        RecordingInfo recordingInfo = this.getRecordingFromEntityFile(file);
        if (recordingInfo != null) {
            this.updateRecordingUrl(recordingInfo);
        }
        return recordingInfo;
    }

    private Set<RecordingInfo> getAllRecordingsFromHost() {
        File folder = new File(this.cloudMediaConfig.getRecordingPath());
        File[] files = folder.listFiles();

        Set<RecordingInfo> recordingInfoEntities = new HashSet<>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory()) {
                File[] innerFiles = files[i].listFiles();
                for (int j = 0; j < innerFiles.length; j++) {
                    RecordingInfo recordingInfo = this.getRecordingFromEntityFile(innerFiles[j]);
                    if (recordingInfo != null) {
                        this.updateRecordingUrl(recordingInfo);
                        recordingInfoEntities.add(recordingInfo);
                    }
                }
            }
        }
        return recordingInfoEntities;
    }

    private Set<String> getRecordingIdsFromHost() {
        File folder = new File(this.cloudMediaConfig.getRecordingPath());
        File[] files = folder.listFiles();

        Set<String> fileNamesNoExtension = new HashSet<>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isDirectory()) {
                File[] innerFiles = files[i].listFiles();
                for (int j = 0; j < innerFiles.length; j++) {
                    if (innerFiles[j].isFile()
                            && innerFiles[j].getName().startsWith(RecordingManager.RECORDING_ENTITY_FILE)) {
                        fileNamesNoExtension
                                .add(innerFiles[j].getName().replaceFirst(RecordingManager.RECORDING_ENTITY_FILE, ""));
                        break;
                    }
                }
            }
        }
        return fileNamesNoExtension;
    }

    private void checkRecordingPaths(String cloudmediaRecordingPath, String cloudmediaRecordingCustomLayout)
            throws CloudMediaException {
        log.info("Initializing recording paths");

        Path recordingPath = null;
        try {
            recordingPath = Files.createDirectories(Paths.get(cloudmediaRecordingPath));
        } catch (IOException e) {
            String errorMessage = "The recording path \"" + cloudmediaRecordingPath
                    + "\" is not valid. Reason: OpenVidu Server cannot find path \"" + cloudmediaRecordingPath
                    + "\" and doesn't have permissions to create it";
            log.error(errorMessage);
            throw new CloudMediaException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
        }

        // Check OpenVidu Server write permissions in recording path
        if (!Files.isWritable(recordingPath)) {
            String errorMessage = "The recording path \"" + cloudmediaRecordingPath
                    + "\" is not valid. Reason: OpenVidu Server needs write permissions. Try running command \"sudo chmod 777 "
                    + cloudmediaRecordingPath + "\"";
            log.error(errorMessage);
            throw new CloudMediaException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
        } else {
            log.info("OpenVidu Server has write permissions on recording path: {}", cloudmediaRecordingPath);
        }

        final String testFolderPath = cloudmediaRecordingPath + "/TEST_RECORDING_PATH_" + System.currentTimeMillis();
        final String testFilePath = testFolderPath + "/TEST_RECORDING_PATH.webm";

        // Check Kurento Media Server write permissions in recording path
        KurentoClientSessionInfo kcSessionInfo = new CloudMediaKurentoClientSessionInfo("TEST_RECORDING_PATH",
                "TEST_RECORDING_PATH");
        MediaPipeline pipeline = this.kcProvider.getKurentoClient(kcSessionInfo).createMediaPipeline();
        RecorderEndpoint recorder = new RecorderEndpoint.Builder(pipeline, "file://" + testFilePath).build();

        final AtomicBoolean kurentoRecorderError = new AtomicBoolean(false);

        recorder.addErrorListener(new EventListener<ErrorEvent>() {
            @Override
            public void onEvent(ErrorEvent event) {
                if (event.getErrorCode() == 6) {
                    // KMS write permissions error
                    kurentoRecorderError.compareAndSet(false, true);
                }
            }
        });

        recorder.record();

        try {
            // Give the error event some time to trigger if necessary
            Thread.sleep(500);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }

        if (kurentoRecorderError.get()) {
            String errorMessage = "The recording path \"" + cloudmediaRecordingPath
                    + "\" is not valid. Reason: Kurento Media Server needs write permissions. Try running command \"sudo chmod 777 "
                    + cloudmediaRecordingPath + "\"";
            log.error(errorMessage);
            throw new CloudMediaException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
        }

        recorder.stop();
        recorder.release();
        pipeline.release();

        log.info("Kurento Media Server has write permissions on recording path: {}", cloudmediaRecordingPath);

        try {
            new CustomFileManager().deleteFolder(testFolderPath);
            log.info("OpenVidu Server has write permissions over files created by Kurento Media Server");
        } catch (IOException e) {
            String errorMessage = "The recording path \"" + cloudmediaRecordingPath
                    + "\" is not valid. Reason: OpenVidu Server does not have write permissions over files created by Kurento Media Server. "
                    + "Try running Kurento Media Server as user \"" + System.getProperty("user.name")
                    + "\" or run OpenVidu Server as superuser";
            log.error(errorMessage);
            log.error("Be aware that a folder \"{}\" was created and should be manually deleted (\"sudo rm -rf {}\")",
                    testFolderPath, testFolderPath);
            throw new CloudMediaException(Code.RECORDING_PATH_NOT_VALID, errorMessage);
        }

        if (cloudMediaConfig.recordingCustomLayoutChanged(cloudmediaRecordingCustomLayout)) {
            // Property openvidu.recording.custom-layout changed
            File dir = new File(cloudmediaRecordingCustomLayout);
            if (dir.exists()) {
                if (!dir.isDirectory()) {
                    String errorMessage = "The custom layouts path \"" + cloudmediaRecordingCustomLayout
                            + "\" is not valid. Reason: path already exists but it is not a directory";
                    log.error(errorMessage);
                    throw new CloudMediaException(Code.RECORDING_FILE_EMPTY_ERROR, errorMessage);
                } else {
                    if (dir.listFiles() == null) {
                        String errorMessage = "The custom layouts path \"" + cloudmediaRecordingCustomLayout
                                + "\" is not valid. Reason: OpenVidu Server needs read permissions. Try running command \"sudo chmod 755 "
                                + cloudmediaRecordingCustomLayout + "\"";
                        log.error(errorMessage);
                        throw new CloudMediaException(Code.RECORDING_FILE_EMPTY_ERROR, errorMessage);
                    } else {
                        log.info("OpenVidu Server has read permissions on custom layout path: {}",
                                cloudmediaRecordingCustomLayout);
                        log.info("Custom layouts path successfully initialized at {}", cloudmediaRecordingCustomLayout);
                    }
                }
            } else {
                try {
                    Files.createDirectories(dir.toPath());
                    log.warn(
                            "OpenVidu custom layouts path (system property 'openvidu.recording.custom-layout') has been created, being folder {}. "
                                    + "It is an empty folder, so no custom layout is currently present",
                            dir.getAbsolutePath());
                } catch (IOException e) {
                    String errorMessage = "The custom layouts path \"" + cloudmediaRecordingCustomLayout
                            + "\" is not valid. Reason: OpenVidu Server cannot find path \""
                            + cloudmediaRecordingCustomLayout + "\" and doesn't have permissions to create it";
                    log.error(errorMessage);
                    throw new CloudMediaException(Code.RECORDING_FILE_EMPTY_ERROR, errorMessage);
                }
            }
        }

        log.info("RecordingInfo path successfully initialized at {}", cloudmediaRecordingPath);
    }

    public static EndReason finalReason(EndReason reason) {
        if (RecordingManager.LAST_PARTICIPANT_LEFT_REASONS.contains(reason)) {
            return EndReason.lastParticipantLeft;
        } else {
            return reason;
        }
    }

}
