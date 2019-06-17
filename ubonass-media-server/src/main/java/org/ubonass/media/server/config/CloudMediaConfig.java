/*
 * (C) Copyright 2017-2019 OpenVidu (https://cloudMedia.io/)
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

package org.ubonass.media.server.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class CloudMediaConfig {

    @Value("${cloudmedia.cluster.enable}")
    private boolean cloudmediaClusterEnable;

    @Value("${cloudmedia.streams.video.max-recv-bandwidth}")
    private int cloudmediaStreamsVideoMaxRecvBandwidth;

    @Value("${cloudmedia.streams.video.min-recv-bandwidth}")
    private int cloudmediaStreamsVideoMinRecvBandwidth;

    @Value("${cloudmedia.streams.video.max-send-bandwidth}")
    private int cloudmediaStreamsVideoMaxSendBandwidth;

    @Value("${cloudmedia.streams.video.min-send-bandwidth}")
    private int cloudmediaStreamsVideoMinSendBandwidth;


    public int getVideoMaxRecvBandwidth() {
        return this.cloudmediaStreamsVideoMaxRecvBandwidth;
    }

    public int getVideoMinRecvBandwidth() {
        return this.cloudmediaStreamsVideoMinRecvBandwidth;
    }

    public int getVideoMaxSendBandwidth() {
        return this.cloudmediaStreamsVideoMaxSendBandwidth;
    }

    public int getVideoMinSendBandwidth() {
        return this.cloudmediaStreamsVideoMinSendBandwidth;
    }

}

