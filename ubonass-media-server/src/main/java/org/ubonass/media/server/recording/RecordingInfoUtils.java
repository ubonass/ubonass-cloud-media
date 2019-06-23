

package org.ubonass.media.server.recording;

import com.google.gson.*;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class RecordingInfoUtils {

    private JsonParser parser;
    private JsonObject json;
    private JsonObject jsonFormat;
    private JsonObject videoStream;
    private JsonObject audioStream;

    private String infoFilePath;

    public RecordingInfoUtils(String infoFilePath) throws FileNotFoundException, IOException, CloudMediaException {

        this.infoFilePath = infoFilePath;
        this.parser = new JsonParser();

        try {
            this.json = parser.parse(new FileReader(infoFilePath)).getAsJsonObject();
        } catch (JsonIOException | JsonSyntaxException e) {
            // RecordingInfo metadata from ffprobe is not a JSON: video file is corrupted
            throw new CloudMediaException(Code.RECORDING_FILE_EMPTY_ERROR, "The recording file is corrupted");
        }
        if (this.json.size() == 0) {
            // RecordingInfo metadata from ffprobe is an emtpy JSON
            throw new CloudMediaException(Code.RECORDING_FILE_EMPTY_ERROR, "The recording file is empty");
        }

        this.jsonFormat = json.get("format").getAsJsonObject();
        JsonArray streams = json.get("streams").getAsJsonArray();

        for (int i = 0; i < streams.size(); i++) {
            JsonObject stream = streams.get(i).getAsJsonObject();
            if ("video".equals(stream.get("codec_type").getAsString())) {
                this.videoStream = stream;
            } else if ("audio".equals(stream.get("codec_type").getAsString())) {
                this.audioStream = stream;
            }
        }
    }

    public double getDurationInSeconds() {
        return jsonFormat.get("duration").getAsDouble();
    }

    public int getSizeInBytes() {
        return jsonFormat.get("size").getAsInt();
    }

    public int getNumberOfStreams() {
        return jsonFormat.get("nb_streams").getAsInt();
    }

    public int getBitRate() {
        return ((jsonFormat.get("bit_rate").getAsInt()) / 1000);
    }

    public boolean hasVideo() {
        return this.videoStream != null;
    }

    public boolean hasAudio() {
        return this.audioStream != null;
    }

    public int videoWidth() {
        return videoStream.get("width").getAsInt();
    }

    public int videoHeight() {
        return videoStream.get("height").getAsInt();
    }

    public int getVideoFramerate() {
        String frameRate = videoStream.get("r_frame_rate").getAsString();
        String[] frameRateParts = frameRate.split("/");

        return Integer.parseInt(frameRateParts[0]) / Integer.parseInt(frameRateParts[1]);
    }

    public String getVideoCodec() {
        return videoStream.get("codec_name").toString();
    }

    public String getLongVideoCodec() {
        return videoStream.get("codec_long_name").toString();
    }

    public String getAudioCodec() {
        return audioStream.get("codec_name").toString();
    }

    public String getLongAudioCodec() {
        return audioStream.get("codec_long_name").toString();
    }

    public boolean deleteFilePath() {
        return new File(this.infoFilePath).delete();
    }

}
