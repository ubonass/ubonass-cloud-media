

package org.ubonass.media.server.recording.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.commons.io.FilenameUtils;
import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.java.client.RecordingProperties;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.EndReason;
import org.ubonass.media.server.core.MediaSession;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.kurento.endpoint.PublisherEndpoint;
import org.ubonass.media.server.kurento.kms.FixedOneKmsManager;
import org.ubonass.media.server.recording.RecorderEndpointWrapper;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SingleStreamRecordingService extends RecordingService {

	private static final Logger log = LoggerFactory.getLogger(SingleStreamRecordingService.class);

	private Map<String, Map<String, RecorderEndpointWrapper>> recorders = new ConcurrentHashMap<>();
	private final String INDIVIDUAL_STREAM_METADATA_FILE = ".stream.";

	public SingleStreamRecordingService(RecordingManager recordingManager, CloudMediaConfig cloudMediaConfig) {
		super(recordingManager, cloudMediaConfig);
	}

	@Override
	public RecordingInfo startRecording(MediaSession session, RecordingProperties properties) throws CloudMediaException {

		PropertiesRecordingId updatePropertiesAndRecordingId = this.setFinalRecordingNameAndGetFreeRecordingId(session,
				properties);
		properties = updatePropertiesAndRecordingId.properties;
		String recordingId = updatePropertiesAndRecordingId.recordingId;

		log.info("Starting individual ({}) recordingInfo {} of session {}",
				properties.hasVideo() ? (properties.hasAudio() ? "video+audio" : "video-only") : "audioOnly",
				recordingId, session.getSessionId());

		RecordingInfo recordingInfo = new RecordingInfo(session.getSessionId(), recordingId, properties);
		this.recordingManager.startingRecordings.put(recordingInfo.getId(), recordingInfo);

		recorders.put(session.getSessionId(), new ConcurrentHashMap<String, RecorderEndpointWrapper>());

		final int activePublishers = session.getActivePublishers();
		final CountDownLatch recordingStartedCountdown = new CountDownLatch(activePublishers);

		for (Participant p : session.getParticipants()) {
			if (p.isStreaming()) {

				MediaProfileSpecType profile = null;
				try {
					profile = generateMediaProfile(properties, p);
				} catch (CloudMediaException e) {
					log.error(
							"Cannot start single stream recorder for stream {} in session {}: {}. Skipping to next stream being published",
							p.getPublisherStreamId(), session.getSessionId(), e.getMessage());
					recordingStartedCountdown.countDown();
					continue;
				}
				this.startRecorderEndpointForPublisherEndpoint(session, recordingId, profile, p,
						recordingStartedCountdown);
			}
		}

		try {
			if (!recordingStartedCountdown.await(5, TimeUnit.SECONDS)) {
				log.error("Error waiting for some recorder endpoint to start in session {}", session.getSessionId());
				throw this.failStartRecording(session, recordingInfo, "Couldn't initialize some RecorderEndpoint");
			}
		} catch (InterruptedException e) {
			recordingInfo.setStatus(RecordingInfo.Status.failed);
			log.error("Exception while waiting for state change", e);
		}

		this.generateRecordingMetadataFile(recordingInfo);
		this.updateRecordingManagerCollections(session, recordingInfo);
		this.sendRecordingStartedNotification(session, recordingInfo);

		return recordingInfo;
	}

	@Override
	public RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason) {
		return this.stopRecording(session, recordingInfo, reason, false);
	}

	public RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason,
													  boolean forceAfterKmsRestart) {
		log.info("Stopping individual ({}) recording {} of session {}. Reason: {}",
				recordingInfo.hasVideo() ? (recordingInfo.hasAudio() ? "video+audio" : "video-only") : "audioOnly",
				recordingInfo.getId(), recordingInfo.getSessionId(), reason);

		final int numberOfActiveRecorders = recorders.get(recordingInfo.getSessionId()).size();
		final CountDownLatch stoppedCountDown = new CountDownLatch(numberOfActiveRecorders);

		for (RecorderEndpointWrapper wrapper : recorders.get(recordingInfo.getSessionId()).values()) {
			this.stopRecorderEndpointOfPublisherEndpoint(recordingInfo.getSessionId(), wrapper.getStreamId(),
					stoppedCountDown, forceAfterKmsRestart);
		}
		try {
			if (!stoppedCountDown.await(5, TimeUnit.SECONDS)) {
				recordingInfo.setStatus(RecordingInfo.Status.failed);
				log.error("Error waiting for some recorder endpoint to stop in session {}", recordingInfo.getSessionId());
			}
		} catch (InterruptedException e) {
			recordingInfo.setStatus(RecordingInfo.Status.failed);
			log.error("Exception while waiting for state change", e);
		}

		this.cleanRecordingMaps(recordingInfo);
		this.recorders.remove(recordingInfo.getSessionId());

		recordingInfo = this.sealMetadataFiles(recordingInfo);

		if (reason != null && session != null) {
			this.recordingManager.sessionHandler.sendRecordingStoppedNotification(session, recordingInfo, reason);
		}

		return recordingInfo;
	}

	public void startRecorderEndpointForPublisherEndpoint(MediaSession session, String recordingId,
			MediaProfileSpecType profile, Participant participant, CountDownLatch globalStartLatch) {
		log.info("Starting single stream recorder for stream {} in session {}", participant.getPublisherStreamId(),
				session.getSessionId());

		if (recordingId == null) {
			// Stream is being recorded because is a new publisher in an ongoing recorded
			// session. If recordingId is defined is because Stream is being recorded from
			// "startRecording" method
			RecordingInfo recordingInfo = this.recordingManager.sessionsRecordings.get(session.getSessionId());
			recordingId = recordingInfo.getId();

			try {
				profile = generateMediaProfile(recordingInfo.getRecordingProperties(), participant);
			} catch (CloudMediaException e) {
				log.error("Cannot start single stream recorder for stream {} in session {}: {}",
						participant.getPublisherStreamId(), session.getSessionId(), e.getMessage());
				return;
			}
		}

		KurentoParticipant kurentoParticipant = (KurentoParticipant) participant;
		MediaPipeline pipeline = kurentoParticipant.getPublisher().getPipeline();

		RecorderEndpoint recorder = new RecorderEndpoint.Builder(pipeline,
				"file://" + this.cloudMediaConfig.getRecordingPath() + recordingId + "/"
						+ participant.getPublisherStreamId() + ".webm").withMediaProfile(profile).build();

		recorder.addRecordingListener(new EventListener<RecordingEvent>() {
			@Override
			public void onEvent(RecordingEvent event) {
				recorders.get(session.getSessionId()).get(participant.getPublisherStreamId())
						.setStartTime(Long.parseLong(event.getTimestampMillis()));
				log.info("RecordingInfo started event for stream {}", participant.getPublisherStreamId());
				globalStartLatch.countDown();
			}
		});

		recorder.addErrorListener(new EventListener<ErrorEvent>() {
			@Override
			public void onEvent(ErrorEvent event) {
				log.error(event.getErrorCode() + " " + event.getDescription());
			}
		});

		connectAccordingToProfile(kurentoParticipant.getPublisher(), recorder, profile);

		RecorderEndpointWrapper wrapper = new RecorderEndpointWrapper(recorder, participant.getParticipantPublicId(),
				recordingId, participant.getPublisherStreamId(), participant.getClientMetadata(),
				participant.getServerMetadata(), kurentoParticipant.getPublisher().getMediaOptions().getHasAudio(),
				kurentoParticipant.getPublisher().getMediaOptions().getHasVideo(),
				kurentoParticipant.getPublisher().getMediaOptions().getTypeOfVideo());

		recorders.get(session.getSessionId()).put(participant.getPublisherStreamId(), wrapper);
		wrapper.getRecorder().record();
	}

	public void stopRecorderEndpointOfPublisherEndpoint(String sessionId, String streamId,
			CountDownLatch globalStopLatch, boolean forceAfterKmsRestart) {
		log.info("Stopping single stream recorder for stream {} in session {}", streamId, sessionId);
		final RecorderEndpointWrapper finalWrapper = this.recorders.get(sessionId).remove(streamId);
		if (finalWrapper != null && !forceAfterKmsRestart) {
			finalWrapper.getRecorder().addStoppedListener(new EventListener<StoppedEvent>() {
				@Override
				public void onEvent(StoppedEvent event) {
					finalWrapper.setEndTime(Long.parseLong(event.getTimestampMillis()));
					generateIndividualMetadataFile(finalWrapper);
					log.info("RecordingInfo stopped event for stream {}", streamId);
					finalWrapper.getRecorder().release();
					globalStopLatch.countDown();
				}
			});
			finalWrapper.getRecorder().stop();
		} else {
			if (forceAfterKmsRestart) {
				finalWrapper.setEndTime(FixedOneKmsManager.TIME_OF_DISCONNECTION.get());
				generateIndividualMetadataFile(finalWrapper);
				log.warn("Forcing individual recording stop after KMS restart for stream {} in session {}", streamId,
						sessionId);
			} else {
				log.error("Stream {} wasn't being recorded in session {}", streamId, sessionId);
			}
			globalStopLatch.countDown();
		}
	}

	private MediaProfileSpecType generateMediaProfile(RecordingProperties properties, Participant participant)
			throws CloudMediaException {

		KurentoParticipant kParticipant = (KurentoParticipant) participant;
		MediaProfileSpecType profile = null;

		boolean streamHasAudio = kParticipant.getPublisher().getMediaOptions().getHasAudio();
		boolean streamHasVideo = kParticipant.getPublisher().getMediaOptions().getHasVideo();
		boolean propertiesHasAudio = properties.hasAudio();
		boolean propertiesHasVideo = properties.hasVideo();

		if (streamHasAudio) {
			if (streamHasVideo) {
				// Stream has both audio and video tracks

				if (propertiesHasAudio) {
					if (propertiesHasVideo) {
						profile = MediaProfileSpecType.WEBM;
					} else {
						profile = MediaProfileSpecType.WEBM_AUDIO_ONLY;
					}
				} else {
					profile = MediaProfileSpecType.WEBM_VIDEO_ONLY;
				}
			} else {
				// Stream has audio track only

				if (propertiesHasAudio) {
					profile = MediaProfileSpecType.WEBM_AUDIO_ONLY;
				} else {
					// ERROR: RecordingProperties set to video only but there's no video track
					throw new CloudMediaException(
							Code.MEDIA_TYPE_STREAM_INCOMPATIBLE_WITH_RECORDING_PROPERTIES_ERROR_CODE,
							"RecordingProperties set to \"hasAudio(false)\" but stream is audio-only");
				}
			}
		} else if (streamHasVideo) {
			// Stream has video track only

			if (propertiesHasVideo) {
				profile = MediaProfileSpecType.WEBM_VIDEO_ONLY;
			} else {
				// ERROR: RecordingProperties set to audio only but there's no audio track
				throw new CloudMediaException(Code.MEDIA_TYPE_STREAM_INCOMPATIBLE_WITH_RECORDING_PROPERTIES_ERROR_CODE,
						"RecordingProperties set to \"hasVideo(false)\" but stream is video-only");
			}
		} else {
			// ERROR: Stream has no track at all. This branch should never be reachable
			throw new CloudMediaException(Code.MEDIA_TYPE_STREAM_INCOMPATIBLE_WITH_RECORDING_PROPERTIES_ERROR_CODE,
					"Stream has no track at all. Cannot be recorded");
		}
		return profile;
	}

	private void connectAccordingToProfile(PublisherEndpoint publisherEndpoint, RecorderEndpoint recorder,
										   MediaProfileSpecType profile) {
		switch (profile) {
		case WEBM:
			publisherEndpoint.connect(recorder, MediaType.AUDIO);
			publisherEndpoint.connect(recorder, MediaType.VIDEO);
			break;
		case WEBM_AUDIO_ONLY:
			publisherEndpoint.connect(recorder, MediaType.AUDIO);
			break;
		case WEBM_VIDEO_ONLY:
			publisherEndpoint.connect(recorder, MediaType.VIDEO);
			break;
		default:
			throw new UnsupportedOperationException("Unsupported profile when single stream recording: " + profile);
		}
	}

	private void generateIndividualMetadataFile(RecorderEndpointWrapper wrapper) {
		String filesPath = this.cloudMediaConfig.getRecordingPath() + wrapper.getRecordingId() + "/";
		File videoFile = new File(filesPath + wrapper.getStreamId() + ".webm");
		wrapper.setSize(videoFile.length());
		String metadataFilePath = filesPath + INDIVIDUAL_STREAM_METADATA_FILE + wrapper.getStreamId();
		String metadataFileContent = wrapper.toJson().toString();
		this.fileWriter.createAndWriteFile(metadataFilePath, metadataFileContent);
	}

	private RecordingInfo sealMetadataFiles(RecordingInfo recordingInfo) {
		// Must update recording "status" (to stopped), "duration" (min startTime of all
		// individual recordings) and "size" (sum of all individual recordings size)

		String folderPath = this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId() + "/";

		String metadataFilePath = folderPath + RecordingManager.RECORDING_ENTITY_FILE + recordingInfo.getId();
		String syncFilePath = folderPath + recordingInfo.getName() + ".json";

		recordingInfo = this.recordingManager.getRecordingFromEntityFile(new File(metadataFilePath));

		long minStartTime = Long.MAX_VALUE;
		long maxEndTime = 0;
		long accumulatedSize = 0;

		File folder = new File(folderPath);
		File[] files = folder.listFiles();

		Reader reader = null;
		Gson gson = new Gson();

		// Sync metadata json object to store in "RECORDING_NAME.json"
		JsonObject json = new JsonObject();
		json.addProperty("createdAt", recordingInfo.getCreatedAt());
		json.addProperty("id", recordingInfo.getId());
		json.addProperty("name", recordingInfo.getName());
		json.addProperty("sessionId", recordingInfo.getSessionId());
		JsonArray jsonArrayFiles = new JsonArray();

		for (int i = 0; i < files.length; i++) {
			if (files[i].isFile() && files[i].getName().startsWith(INDIVIDUAL_STREAM_METADATA_FILE)) {
				try {
					reader = new FileReader(files[i].getAbsolutePath());
				} catch (FileNotFoundException e) {
					log.error("Error reading file {}. Error: {}", files[i].getAbsolutePath(), e.getMessage());
				}
				RecorderEndpointWrapper wr = gson.fromJson(reader, RecorderEndpointWrapper.class);
				minStartTime = Math.min(minStartTime, wr.getStartTime());
				maxEndTime = Math.max(maxEndTime, wr.getEndTime());
				accumulatedSize += wr.getSize();

				JsonObject jsonFile = new JsonObject();
				jsonFile.addProperty("connectionId", wr.getConnectionId());
				jsonFile.addProperty("streamId", wr.getStreamId());
				jsonFile.addProperty("size", wr.getSize());
				jsonFile.addProperty("clientData", wr.getClientData());
				jsonFile.addProperty("serverData", wr.getServerData());
				jsonFile.addProperty("hasAudio", wr.isHasAudio() && recordingInfo.hasAudio());
				jsonFile.addProperty("hasVideo", wr.isHasVideo() && recordingInfo.hasVideo());
				if (wr.isHasVideo()) {
					jsonFile.addProperty("typeOfVideo", wr.getTypeOfVideo());
				}
				jsonFile.addProperty("startTimeOffset", wr.getStartTime() - recordingInfo.getCreatedAt());
				jsonFile.addProperty("endTimeOffset", wr.getEndTime() - recordingInfo.getCreatedAt());

				jsonArrayFiles.add(jsonFile);
			}
		}

		json.add("files", jsonArrayFiles);
		this.fileWriter.createAndWriteFile(syncFilePath, new GsonBuilder().setPrettyPrinting().create().toJson(json));
		this.generateZipFileAndCleanFolder(folderPath, recordingInfo.getName() + ".zip");

		double duration = (double) (maxEndTime - minStartTime) / 1000;
		duration = duration > 0 ? duration : 0;

		recordingInfo = this.sealRecordingMetadataFile(recordingInfo, accumulatedSize, duration, metadataFilePath);

		return recordingInfo;
	}

	private void generateZipFileAndCleanFolder(String folder, String fileName) {
		FileOutputStream fos = null;
		ZipOutputStream zipOut = null;

		final File[] files = new File(folder).listFiles();

		try {
			fos = new FileOutputStream(folder + fileName);
			zipOut = new ZipOutputStream(fos);

			for (int i = 0; i < files.length; i++) {
				String fileExtension = FilenameUtils.getExtension(files[i].getName());

				if (files[i].isFile() && (fileExtension.equals("json") || fileExtension.equals("webm"))) {

					// Zip video files and json sync metadata file
					FileInputStream fis = new FileInputStream(files[i]);
					ZipEntry zipEntry = new ZipEntry(files[i].getName());
					zipOut.putNextEntry(zipEntry);
					byte[] bytes = new byte[1024];
					int length;
					while ((length = fis.read(bytes)) >= 0) {
						zipOut.write(bytes, 0, length);
					}
					fis.close();

				}
				if (!files[i].getName().startsWith(RecordingManager.RECORDING_ENTITY_FILE)) {
					// Clean inspected file if it is not
					files[i].delete();
				}
			}
		} catch (IOException e) {
			log.error("Error generating ZIP file {}. Error: {}", folder + fileName, e.getMessage());
		} finally {
			try {
				zipOut.close();
				fos.close();
				this.updateFilePermissions(folder);
			} catch (IOException e) {
				log.error("Error closing FileOutputStream or ZipOutputStream. Error: {}", e.getMessage());
				e.printStackTrace();
			}
		}
	}

}
