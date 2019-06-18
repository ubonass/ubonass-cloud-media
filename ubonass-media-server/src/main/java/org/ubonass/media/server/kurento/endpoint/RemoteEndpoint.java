
package org.ubonass.media.server.kurento.endpoint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.core.MediaOptions;
import org.ubonass.media.server.kurento.TrackType;
import org.ubonass.media.server.kurento.core.KurentoParticipant;
import org.ubonass.media.server.utils.JsonUtils;

import java.util.*;
import java.util.Map.Entry;

/**
 * Publisher aspect of the {@link MediaEndpoint}.
 *
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class RemoteEndpoint extends MediaEndpoint {

    private final static Logger log = LoggerFactory.getLogger(RemoteEndpoint.class);

    public RemoteEndpoint(KurentoParticipant owner,
                          String endpointName,
                          MediaPipeline pipeline,
                          CloudMediaConfig cloudMediaConfig) {
        super(false, owner, endpointName, pipeline, cloudMediaConfig, log);
    }

    public synchronized String startProcessOfferOrAnswer(SdpType sdpType,
                                                String sdpString) {
        return processOfferOrAnswer(sdpType, sdpString);
    }

    public synchronized String prepareRemoteConnection() {
        return generateOffer();
    }
}
