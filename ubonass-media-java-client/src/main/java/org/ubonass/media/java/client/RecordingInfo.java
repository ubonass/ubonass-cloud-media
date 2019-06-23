

package org.ubonass.media.java.client;

import com.google.gson.JsonObject;

/**
 * See {@link org.ubonass.media.java.client.CloudMedia#startRecording(String)}
 */
public class RecordingInfo {

	/**
	 * See {@link RecordingInfo#getStatus()}
	 */
	public enum Status {

		/**
		 * The recording is starting (cannot be stopped)
		 */
		starting,

		/**
		 * The recording has started and is going on
		 */
		started,

		/**
		 * The recording has finished OK
		 */
		stopped,

		/**
		 * The recording is available for downloading. This status is reached for all
		 * stopped recordings if
		 * <a href="https://openvidu.io/docs/reference-docs/openvidu-server-params/"
		 * target="_blank">OpenVidu Server configuration</a> property
		 * <code>openvidu.recording.public-access</code> is true
		 */
		available,

		/**
		 * The recording has failed
		 */
		failed;
	}

	/**
	 * See {@link RecordingInfo#getOutputMode()}
	 */
	public enum OutputMode {

		/**
		 * Record all streams in a grid layout in a single archive
		 */
		COMPOSED,

		/**
		 * Record each stream individually
		 */
		INDIVIDUAL;
	}

	private RecordingInfo.Status status;

	private String id;
	private String sessionId;
	private long createdAt; // milliseconds (UNIX Epoch time)
	private long size = 0; // bytes
	private double duration = 0; // seconds
	private String url;
	private String resolution;
	private boolean hasAudio = true;
	private boolean hasVideo = true;
	private RecordingProperties recordingProperties;

	public RecordingInfo(String sessionId, String id, RecordingProperties recordingProperties) {
		this.sessionId = sessionId;
		this.createdAt = System.currentTimeMillis();
		this.id = id;
		this.status = RecordingInfo.Status.started;
		this.recordingProperties = recordingProperties;
		this.resolution = this.recordingProperties.resolution();
		this.hasAudio = this.recordingProperties.hasAudio();
		this.hasVideo = this.recordingProperties.hasVideo();
	}

	public RecordingInfo(JsonObject json) {
		this.id = json.get("id").getAsString();
		this.sessionId = json.get("sessionId").getAsString();
		this.createdAt = json.get("createdAt").getAsLong();
		this.size = json.get("size").getAsLong();
		try {
			this.duration = json.get("duration").getAsDouble();
		} catch (Exception e) {
			this.duration = new Long((long) json.get("duration").getAsLong()).doubleValue();
		}
		if (json.get("url").isJsonNull()) {
			this.url = null;
		} else {
			this.url = json.get("url").getAsString();
		}
		this.hasAudio = json.get("hasAudio").getAsBoolean();
		this.hasVideo = json.get("hasVideo").getAsBoolean();
		this.status = RecordingInfo.Status.valueOf(json.get("status").getAsString());

		RecordingInfo.OutputMode outputMode = RecordingInfo.OutputMode
				.valueOf(json.get("outputMode").getAsString());
		RecordingProperties.Builder builder = new RecordingProperties.Builder().name(json.get("name").getAsString())
				.outputMode(outputMode).hasAudio(this.hasAudio).hasVideo(this.hasVideo);
		if (RecordingInfo.OutputMode.COMPOSED.equals(outputMode) && this.hasVideo) {
			this.resolution = json.get("resolution").getAsString();
			builder.resolution(this.resolution);
			RecordingLayout recordingLayout = RecordingLayout.valueOf(json.get("recordingLayout").getAsString());
			builder.recordingLayout(recordingLayout);
			if (RecordingLayout.CUSTOM.equals(recordingLayout)) {
				builder.customLayout(json.get("customLayout").getAsString());
			}
		}
		this.recordingProperties = builder.build();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("id", this.id);
		json.addProperty("name", this.recordingProperties.name());
		json.addProperty("outputMode", this.getOutputMode().name());
		if (RecordingInfo.OutputMode.COMPOSED.equals(this.recordingProperties.outputMode())
				&& this.hasVideo) {
			json.addProperty("resolution", this.resolution);
			json.addProperty("recordingLayout", this.recordingProperties.recordingLayout().name());
			if (RecordingLayout.CUSTOM.equals(this.recordingProperties.recordingLayout())) {
				json.addProperty("customLayout", this.recordingProperties.customLayout());
			}
		}
		json.addProperty("sessionId", this.sessionId);
		json.addProperty("createdAt", this.createdAt);
		json.addProperty("size", this.size);
		json.addProperty("duration", this.duration);
		json.addProperty("url", this.url);
		json.addProperty("hasAudio", this.hasAudio);
		json.addProperty("hasVideo", this.hasVideo);
		json.addProperty("status", this.status.toString());
		return json;
	}

	/**
	 *
	 * @return
	 */
	public RecordingProperties getRecordingProperties() {
		return this.recordingProperties;
	}
	/**
	 *
	 * @param status
	 */
	public void setStatus(Status status) {
		this.status = status;
	}

	/**
	 * Status of the recording
	 */
	public RecordingInfo.Status getStatus() {
		return status;
	}

	/**
	 * RecordingInfo unique identifier
	 */
	public String getId() {
		return id;
	}

	/**
	 * Name of the recording. The video file will be named after this property. You
	 * can access this same value in your clients on recording events
	 * (<code>recordingStarted</code>, <code>recordingStopped</code>)
	 */
	public String getName() {
		return this.recordingProperties.name();
	}

	/**
	 * Mode of recording: COMPOSED for a single archive in a grid layout or
	 * INDIVIDUAL for one archive for each stream
	 */
	public OutputMode getOutputMode() {
		return this.recordingProperties.outputMode();
	}

	/**
	 * The layout used in this recording. Only defined if OutputMode is COMPOSED
	 */
	public RecordingLayout getRecordingLayout() {
		return this.recordingProperties.recordingLayout();
	}

	/**
	 * The custom layout used in this recording. Only defined if if OutputMode is
	 * COMPOSED and
	 * {@link org.ubonass.media.java.client.RecordingProperties.Builder#customLayout(String)}
	 * has been called
	 */
	public String getCustomLayout() {
		return this.recordingProperties.customLayout();
	}

	/**
	 * Session associated to the recording
	 */
	public String getSessionId() {
		return sessionId;
	}

	/**
	 *
	 * @param createdAt
	 */
	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}

	/**
	 * Time when the recording started in UTC milliseconds
	 */
	public long getCreatedAt() {
		return createdAt;
	}

	/**
	 *
	 * @param l
	 */
	public void setSize(long l) { this.size = l; }
	/**
	 * Size of the recording in bytes (0 until the recording is stopped)
	 */
	public long getSize() {
		return size;
	}

	/**
	 *
	 * @param duration
	 */
	public void setDuration(double duration) {
		this.duration = duration;
	}
	/**
	 * Duration of the recording in seconds (0 until the recording is stopped)
	 */
	public double getDuration() {
		return duration;
	}

	/**
	 *
	 * @param url
	 */
	public void setUrl(String url) {
		this.url = url;
	}

	/**
	 * URL of the recording. You can access the file from there. It is
	 * <code>null</code> until recording is stopped or if
	 * <a href="https://openvidu.io/docs/reference-docs/openvidu-server-params/"
	 * target="_blank">OpenVidu Server configuration</a> property
	 * <code>openvidu.recording.public-access</code> is false
	 */
	public String getUrl() {
		return url;
	}

	/**
	 *
	 * @param resolution
	 */
	public void setResolution(String resolution) {
		this.resolution = resolution;
	}

	/**
	 * Resolution of the video file. Only defined if OutputMode of the RecordingInfo is
	 * set to {@link RecordingInfo.OutputMode#COMPOSED}
	 */
	public String getResolution() {
		return this.recordingProperties.resolution();
	}

	/**
	 *
	 * @param hasAudio
	 */
	public void setHasAudio(boolean hasAudio) {
		this.hasAudio = hasAudio;
	}
	/**
	 * <code>true</code> if the recording has an audio track, <code>false</code>
	 * otherwise (currently fixed to true)
	 */
	public boolean hasAudio() {
		return this.recordingProperties.hasAudio();
	}

	/**
	 *
	 * @param hasVideo
	 */
	public void setHasVideo(boolean hasVideo) {
		this.hasVideo = hasVideo;
	}

	/**
	 * <code>true</code> if the recording has a video track, <code>false</code>
	 * otherwise (currently fixed to true)
	 */
	public boolean hasVideo() {
		return this.recordingProperties.hasVideo();
	}

}
