

package org.ubonass.media.server.recording.service;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.java.client.RecordingInfo;
import org.ubonass.media.java.client.RecordingLayout;
import org.ubonass.media.java.client.RecordingProperties;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.EndReason;
import org.ubonass.media.server.core.MediaSession;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.kurento.core.KurentoMediaSession;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.recording.CompositeWrapper;
import org.ubonass.media.server.recording.RecordingInfoUtils;
import org.ubonass.media.server.utils.DockerManager;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ComposedRecordingService extends RecordingService {

	private static final Logger log = LoggerFactory.getLogger(ComposedRecordingService.class);

	private Map<String, String> containers = new ConcurrentHashMap<>();
	private Map<String, String> sessionsContainers = new ConcurrentHashMap<>();
	private Map<String, CompositeWrapper> composites = new ConcurrentHashMap<>();

	private DockerManager dockerManager;

	public ComposedRecordingService(RecordingManager recordingManager, CloudMediaConfig cloudMediaConfig) {
		super(recordingManager, cloudMediaConfig);
		this.dockerManager = new DockerManager();
	}

	@Override
	public RecordingInfo startRecording(MediaSession session, RecordingProperties properties) throws CloudMediaException {

		PropertiesRecordingId updatePropertiesAndRecordingId = this.setFinalRecordingNameAndGetFreeRecordingId(session,
				properties);
		properties = updatePropertiesAndRecordingId.properties;
		String recordingId = updatePropertiesAndRecordingId.recordingId;

		// Instantiate and store recording object
		RecordingInfo recordingInfo = new RecordingInfo(session.getSessionId(), recordingId, properties);
		this.recordingManager.startingRecordings.put(recordingInfo.getId(), recordingInfo);

		if (properties.hasVideo()) {
			// Docker container used
			recordingInfo = this.startRecordingWithVideo(session, recordingInfo, properties);
		} else {
			// Kurento composite used
			recordingInfo = this.startRecordingAudioOnly(session, recordingInfo, properties);
		}

		this.updateRecordingManagerCollections(session, recordingInfo);

		return recordingInfo;
	}

	@Override
	public RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason) {
		return this.stopRecording(session, recordingInfo, reason, false);
	}

	public RecordingInfo stopRecording(MediaSession session, RecordingInfo recordingInfo, EndReason reason,
													  boolean forceAfterKmsRestart) {
		if (recordingInfo.hasVideo()) {
			return this.stopRecordingWithVideo(session, recordingInfo, reason);
		} else {
			return this.stopRecordingAudioOnly(session, recordingInfo, reason, forceAfterKmsRestart);
		}
	}

	public void joinPublisherEndpointToComposite(MediaSession session, String recordingId, Participant participant)
			throws CloudMediaException {
		log.info("Joining single stream {} to Composite in session {}", participant.getPublisherStreamId(),
				session.getSessionId());

		KurentoParticipant kurentoParticipant = (KurentoParticipant) participant;
		CompositeWrapper compositeWrapper = this.composites.get(session.getSessionId());

		try {
			compositeWrapper.connectPublisherEndpoint(kurentoParticipant.getPublisher());
		} catch (CloudMediaException e) {
			if (Code.RECORDING_START_ERROR_CODE.getValue() == e.getCodeValue()) {
				// First user publishing triggered RecorderEnpoint start, but it failed
				throw e;
			}
		}
	}

	public void removePublisherEndpointFromComposite(String sessionId, String streamId) {
		CompositeWrapper compositeWrapper = this.composites.get(sessionId);
		compositeWrapper.disconnectPublisherEndpoint(streamId);
	}

	private RecordingInfo startRecordingWithVideo(MediaSession session, RecordingInfo recordingInfo, RecordingProperties properties)
			throws CloudMediaException {

		log.info("Starting composed ({}) recording {} of session {}",
				properties.hasAudio() ? "video + audio" : "audio-only", recordingInfo.getId(), recordingInfo.getSessionId());

		List<String> envs = new ArrayList<>();

		String layoutUrl = this.getLayoutUrl(recordingInfo, this.getShortSessionId(session));

		envs.add("URL=" + layoutUrl);
		envs.add("ONLY_VIDEO=" + !properties.hasAudio());
		envs.add("RESOLUTION=" + properties.resolution());
		envs.add("FRAMERATE=30");
		envs.add("VIDEO_ID=" + recordingInfo.getId());
		envs.add("VIDEO_NAME=" + properties.name());
		envs.add("VIDEO_FORMAT=mp4");
		envs.add("RECORDING_JSON=" + recordingInfo.toJson().toString());

		log.info(recordingInfo.toJson().toString());
		log.info("Recorder connecting to url {}", layoutUrl);

		String containerId;
		try {
			final String container = RecordingManager.IMAGE_NAME + ":" + RecordingManager.IMAGE_TAG;
			final String containerName = "recording_" + recordingInfo.getId();
			Volume volume1 = new Volume("/recordings");
			Volume volume2 = new Volume("/dev/shm");
			List<Volume> volumes = new ArrayList<>();
			volumes.add(volume1);
			volumes.add(volume2);
			Bind bind1 = new Bind(cloudMediaConfig.getRecordingPath(), volume1);
			Bind bind2 = new Bind("/dev/shm", volume2);
			List<Bind> binds = new ArrayList<>();
			binds.add(bind1);
			binds.add(bind2);
			containerId = dockerManager.runContainer(container, containerName, volumes, binds, null, "host", envs);
			containers.put(containerId, containerName);
		} catch (Exception e) {
			this.cleanRecordingMaps(recordingInfo);
			throw this.failStartRecording(session, recordingInfo,
					"Couldn't initialize recording container. Error: " + e.getMessage());
		}

		this.sessionsContainers.put(session.getSessionId(), containerId);

		try {
			this.waitForVideoFileNotEmpty(recordingInfo);
		} catch (CloudMediaException e) {
			this.cleanRecordingMaps(recordingInfo);
			throw this.failStartRecording(session, recordingInfo,
					"Couldn't initialize recording container. Error: " + e.getMessage());
		}

		return recordingInfo;
	}

	private RecordingInfo startRecordingAudioOnly(MediaSession session, RecordingInfo recordingInfo, RecordingProperties properties)
			throws CloudMediaException {

		log.info("Starting composed (audio-only) recording {} of session {}", recordingInfo.getId(),
				recordingInfo.getSessionId());

		CompositeWrapper compositeWrapper = new CompositeWrapper((KurentoMediaSession) session,
				"file://" + this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId() + "/" + properties.name()
						+ ".webm");
		this.composites.put(session.getSessionId(), compositeWrapper);

		for (Participant p : session.getParticipants()) {
			if (p.isStreaming()) {
				try {
					this.joinPublisherEndpointToComposite(session, recordingInfo.getId(), p);
				} catch (CloudMediaException e) {
					log.error("Error waiting for RecorderEndpooint of Composite to start in session {}",
							session.getSessionId());
					throw this.failStartRecording(session, recordingInfo, e.getMessage());
				}
			}
		}

		this.generateRecordingMetadataFile(recordingInfo);
		this.sendRecordingStartedNotification(session, recordingInfo);

		return recordingInfo;
	}

	private RecordingInfo stopRecordingWithVideo(MediaSession session, RecordingInfo recordingInfo, EndReason reason) {

		log.info("Stopping composed ({}) recording {} of session {}. Reason: {}",
				recordingInfo.hasAudio() ? "video + audio" : "audio-only", recordingInfo.getId(), recordingInfo.getSessionId(),
				RecordingManager.finalReason(reason));

		String containerId = this.sessionsContainers.remove(recordingInfo.getSessionId());
		this.cleanRecordingMaps(recordingInfo);

		final String recordingId = recordingInfo.getId();

		if (session == null) {
			log.warn(
					"Existing recording {} does not have an active session associated. This usually means a custom recording"
							+ " layout did not join a recorded participant or the recording has been automatically"
							+ " stopped after last user left and timeout passed",
					recordingInfo.getId());
		}

		if (containerId == null) {

			// MediaSession was closed while recording container was initializing
			// Wait until containerId is available and force its stop and removal
			new Thread(() -> {
				log.warn("MediaSession closed while starting recording container");
				boolean containerClosed = false;
				String containerIdAux;
				int i = 0;
				final int timeout = 30;
				while (!containerClosed && (i < timeout)) {
					containerIdAux = this.sessionsContainers.remove(session.getSessionId());
					if (containerIdAux == null) {
						try {
							log.warn("Waiting for container to be launched...");
							i++;
							Thread.sleep(500);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} else {
						log.warn("Removing container {} for closed session {}...", containerIdAux,
								session.getSessionId());
						dockerManager.stopDockerContainer(containerIdAux);
						dockerManager.removeDockerContainer(containerIdAux, false);
						containers.remove(containerId);
						containerClosed = true;
						log.warn("Container {} for closed session {} succesfully stopped and removed", containerIdAux,
								session.getSessionId());
						log.warn("Deleting unusable files for recording {}", recordingId);
						if (HttpStatus.NO_CONTENT
								.equals(this.recordingManager.deleteRecordingFromHost(recordingId, true))) {
							log.warn("Files properly deleted");
						}
					}
				}
				if (i == timeout) {
					log.error("Container did not launched in {} seconds", timeout / 2);
					return;
				}
			}).start();

		} else {

			// Gracefully stop ffmpeg process
			try {
				dockerManager.runCommandInContainer(containerId, "echo 'q' > stop", 0);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}

			// Wait for the container to be gracefully self-stopped
			final int timeOfWait = 30;
			try {
				dockerManager.waitForContainerStopped(containerId, timeOfWait);
			} catch (Exception e) {
				failRecordingCompletion(recordingInfo, containerId,
						new CloudMediaException(Code.RECORDING_COMPLETION_ERROR_CODE,
								"The recording completion process couldn't finish in " + timeOfWait + " seconds"));
			}

			// Remove container
			dockerManager.removeDockerContainer(containerId, false);
			containers.remove(containerId);

			// Update recording attributes reading from video report file
			try {
				RecordingInfoUtils infoUtils = new RecordingInfoUtils(
						this.cloudMediaConfig.getRecordingPath() + recordingId + "/" + recordingId + ".info");

				if (!infoUtils.hasVideo()) {
					log.error("COMPOSED recording {} with hasVideo=true has not video track", recordingId);
					recordingInfo.setStatus(RecordingInfo.Status.failed);
				} else {
					recordingInfo.setStatus(RecordingInfo.Status.stopped);
					recordingInfo.setDuration(infoUtils.getDurationInSeconds());
					recordingInfo.setSize(infoUtils.getSizeInBytes());
					recordingInfo.setResolution(infoUtils.videoWidth() + "x" + infoUtils.videoHeight());
					recordingInfo.setHasAudio(infoUtils.hasAudio());
					recordingInfo.setHasVideo(infoUtils.hasVideo());
				}

				infoUtils.deleteFilePath();

				recordingInfo = this.recordingManager.updateRecordingUrl(recordingInfo);

			} catch (IOException e) {
				recordingInfo.setStatus(RecordingInfo.Status.failed);
				throw new CloudMediaException(Code.RECORDING_REPORT_ERROR_CODE,
						"There was an error generating the metadata report file for the recording");
			}
			if (session != null && reason != null) {
				this.recordingManager.sessionHandler.sendRecordingStoppedNotification(session, recordingInfo, reason);
			}
		}
		return recordingInfo;
	}

	private RecordingInfo stopRecordingAudioOnly(MediaSession session, RecordingInfo recordingInfo, EndReason reason,
																boolean forceAfterKmsRestart) {

		log.info("Stopping composed (audio-only) recording {} of session {}. Reason: {}", recordingInfo.getId(),
				recordingInfo.getSessionId(), reason);

		String sessionId;
		if (session == null) {
			log.warn(
					"Existing recording {} does not have an active session associated. This means the recording "
							+ "has been automatically stopped after last user left and {} seconds timeout passed",
					recordingInfo.getId(), this.cloudMediaConfig.getRecordingAutostopTimeout());
			sessionId = recordingInfo.getSessionId();
		} else {
			sessionId = session.getSessionId();
		}

		CompositeWrapper compositeWrapper = this.composites.remove(sessionId);

		final CountDownLatch stoppedCountDown = new CountDownLatch(1);

		compositeWrapper.stopCompositeRecording(stoppedCountDown, forceAfterKmsRestart);
		try {
			if (!stoppedCountDown.await(5, TimeUnit.SECONDS)) {
				recordingInfo.setStatus(RecordingInfo.Status.failed);
				log.error("Error waiting for RecorderEndpoint of Composite to stop in session {}",
						recordingInfo.getSessionId());
			}
		} catch (InterruptedException e) {
			recordingInfo.setStatus(RecordingInfo.Status.failed);
			log.error("Exception while waiting for state change", e);
		}

		compositeWrapper.disconnectAllPublisherEndpoints();

		this.cleanRecordingMaps(recordingInfo);

		String filesPath = this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId() + "/";
		File videoFile = new File(filesPath + recordingInfo.getName() + ".webm");
		long finalSize = videoFile.length();
		double finalDuration = (double) compositeWrapper.getDuration() / 1000;

		this.updateFilePermissions(filesPath);

		this.sealRecordingMetadataFile(recordingInfo, finalSize, finalDuration,
				filesPath + RecordingManager.RECORDING_ENTITY_FILE + recordingInfo.getId());

		if (reason != null && session != null) {
			this.recordingManager.sessionHandler.sendRecordingStoppedNotification(session, recordingInfo, reason);
		}

		return recordingInfo;
	}

	private void waitForVideoFileNotEmpty(RecordingInfo recordingInfo) throws CloudMediaException {
		boolean isPresent = false;
		int i = 1;
		int timeout = 150; // Wait for 150*150 = 22500 = 22.5 seconds
		while (!isPresent && timeout <= 150) {
			try {
				Thread.sleep(150);
				timeout++;
				File f = new File(this.cloudMediaConfig.getRecordingPath() + recordingInfo.getId() + "/"
						+ recordingInfo.getName() + ".mp4");
				isPresent = ((f.isFile()) && (f.length() > 0));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		if (i == timeout) {
			log.error("Recorder container failed generating video file (is empty) for session {}",
					recordingInfo.getSessionId());
			throw new CloudMediaException(Code.RECORDING_START_ERROR_CODE,
					"Recorder container failed generating video file (is empty)");
		}
	}

	private void failRecordingCompletion(RecordingInfo recordingInfo, String containerId, CloudMediaException e)
			throws CloudMediaException {
		recordingInfo.setStatus(RecordingInfo.Status.failed);
		dockerManager.stopDockerContainer(containerId);
		dockerManager.removeDockerContainer(containerId, true);
		containers.remove(containerId);
		throw e;
	}

	private String getLayoutUrl(RecordingInfo recordingInfo, String shortSessionId) {
		String secret = cloudMediaConfig.getSecret();
		boolean recordingUrlDefined = cloudMediaConfig.getRecordingComposedUrl() != null
				&& !cloudMediaConfig.getRecordingComposedUrl().isEmpty();
		String recordingUrl = recordingUrlDefined ? cloudMediaConfig.getRecordingComposedUrl()
				: cloudMediaConfig.getWsUrl();
		recordingUrl = recordingUrl.replaceFirst("wss://", "").replaceFirst("https://", "");
		boolean startsWithHttp = recordingUrl.startsWith("http://") || recordingUrl.startsWith("ws://");

		if (startsWithHttp) {
			recordingUrl = recordingUrl.replaceFirst("http://", "").replaceFirst("ws://", "");
		}

		if (recordingUrl.endsWith("/")) {
			recordingUrl = recordingUrl.substring(0, recordingUrl.length() - 1);
		}

		String layout, finalUrl;
		if (RecordingLayout.CUSTOM.equals(recordingInfo.getRecordingLayout())) {
			layout = recordingInfo.getCustomLayout();
			if (!layout.isEmpty()) {
				layout = layout.startsWith("/") ? layout : ("/" + layout);
				layout = layout.endsWith("/") ? layout.substring(0, layout.length() - 1) : layout;
			}
			layout += "/index.html";
			finalUrl = (startsWithHttp ? "http" : "https") + "://OPENVIDUAPP:" + secret + "@" + recordingUrl
					+ "/layouts/custom" + layout + "?sessionId=" + shortSessionId + "&secret=" + secret;
		} else {
			layout = recordingInfo.getRecordingLayout().name().toLowerCase().replaceAll("_", "-");
			int port = startsWithHttp ? 80 : 443;
			try {
				port = new URL(cloudMediaConfig.getFinalUrl()).getPort();
			} catch (MalformedURLException e) {
				log.error(e.getMessage());
			}
			finalUrl = (startsWithHttp ? "http" : "https") + "://OPENVIDUAPP:" + secret + "@" + recordingUrl
					+ "/#/layout-" + layout + "/" + shortSessionId + "/" + secret + "/" + port + "/"
					+ !recordingInfo.hasAudio();
		}

		return finalUrl;
	}

}
