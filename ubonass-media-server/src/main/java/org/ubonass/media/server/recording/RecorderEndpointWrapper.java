
package org.ubonass.media.server.recording;

import com.google.gson.JsonObject;
import lombok.Data;
import org.kurento.client.RecorderEndpoint;

@Data
public class RecorderEndpointWrapper {

    private RecorderEndpoint recorder;
    private String connectionId;
    private String recordingId;
    private String streamId;
    private String clientData;
    private String serverData;
    private boolean hasAudio;
    private boolean hasVideo;
    private String typeOfVideo;

    private long startTime;
    private long endTime;
    private long size;

    public RecorderEndpointWrapper(RecorderEndpoint recorder, String connectionId, String recordingId, String streamId,
                                   String clientData, String serverData, boolean hasAudio, boolean hasVideo, String typeOfVideo) {
        this.recorder = recorder;
        this.connectionId = connectionId;
        this.recordingId = recordingId;
        this.streamId = streamId;
        this.clientData = clientData;
        this.serverData = serverData;
        this.hasAudio = hasAudio;
        this.hasVideo = hasVideo;
        this.typeOfVideo = typeOfVideo;
    }


    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("connectionId", this.connectionId);
        json.addProperty("streamId", this.streamId);
        json.addProperty("clientData", this.clientData);
        json.addProperty("serverData", this.serverData);
        json.addProperty("startTime", this.startTime);
        json.addProperty("endTime", this.endTime);
        json.addProperty("duration", this.endTime - this.startTime);
        json.addProperty("size", this.size);
        json.addProperty("hasAudio", this.hasAudio);
        json.addProperty("hasVideo", this.hasVideo);
        if (this.hasVideo) {
            json.addProperty("typeOfVideo", this.typeOfVideo);
        }
        return json;
    }

}
