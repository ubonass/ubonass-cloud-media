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

package org.ubonass.media.server.recording;

import org.kurento.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.kurento.core.KurentoMediaSession;
import org.ubonass.media.server.kurento.endpoint.PublisherEndpoint;
import org.ubonass.media.server.kurento.kms.FixedOneKmsManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CompositeWrapper {

    private static final Logger log = LoggerFactory.getLogger(CompositeWrapper.class);

    KurentoMediaSession session;
    Composite composite;
    RecorderEndpoint recorderEndpoint;
    HubPort compositeToRecorderHubPort;
    Map<String, HubPort> hubPorts = new ConcurrentHashMap<>();
    Map<String, PublisherEndpoint> publisherEndpoints = new ConcurrentHashMap<>();

    AtomicBoolean isRecording = new AtomicBoolean(false);
    long startTime;
    long endTime;
    long size;

    public CompositeWrapper(KurentoMediaSession session, String path) {
        this.session = session;
        this.composite = new Composite.Builder(session.getPipeline()).build();
        this.recorderEndpoint = new RecorderEndpoint.Builder(composite.getMediaPipeline(), path)
                .withMediaProfile(MediaProfileSpecType.WEBM_AUDIO_ONLY).build();
        this.compositeToRecorderHubPort = new HubPort.Builder(composite).build();
        this.compositeToRecorderHubPort.connect(recorderEndpoint);
    }

    public synchronized void startCompositeRecording(CountDownLatch startLatch) {

        this.recorderEndpoint.addRecordingListener(new EventListener<RecordingEvent>() {
            @Override
            public void onEvent(RecordingEvent event) {
                startTime = Long.parseLong(event.getTimestampMillis());
                log.info("Recording started event for audio-only RecorderEndpoint of Composite in session {}",
                        session.getSessionId());
                startLatch.countDown();
            }
        });

        this.recorderEndpoint.addErrorListener(new EventListener<ErrorEvent>() {
            @Override
            public void onEvent(ErrorEvent event) {
                log.error(event.getErrorCode() + " " + event.getDescription());
            }
        });

        this.recorderEndpoint.record();
    }

    public synchronized void stopCompositeRecording(CountDownLatch stopLatch, boolean forceAfterKmsRestart) {
        if (!forceAfterKmsRestart) {
            this.recorderEndpoint.addStoppedListener(new EventListener<StoppedEvent>() {
                @Override
                public void onEvent(StoppedEvent event) {
                    endTime = Long.parseLong(event.getTimestampMillis());
                    log.info("Recording stopped event for audio-only RecorderEndpoint of Composite in session {}",
                            session.getSessionId());
                    recorderEndpoint.release();
                    compositeToRecorderHubPort.release();
                    stopLatch.countDown();
                }
            });
            this.recorderEndpoint.stop();
        } else {
            endTime = FixedOneKmsManager.TIME_OF_DISCONNECTION.get();
            stopLatch.countDown();
            log.warn("Forcing composed audio-only recording stop after KMS restart in session {}",
                    this.session.getSessionId());
        }

    }

    public void connectPublisherEndpoint(PublisherEndpoint endpoint) throws CloudMediaException {
        HubPort hubPort = new HubPort.Builder(composite).build();
        endpoint.connect(hubPort);
        String streamId = endpoint.getOwner().getPublisherStreamId();
        this.hubPorts.put(streamId, hubPort);
        this.publisherEndpoints.put(streamId, endpoint);

        if (isRecording.compareAndSet(false, true)) {
            // First user publishing. Starting RecorderEndpoint

            log.info("First stream ({}) joined to Composite in session {}. Starting RecorderEndpoint for Composite",
                    streamId, session.getSessionId());

            final CountDownLatch startLatch = new CountDownLatch(1);
            this.startCompositeRecording(startLatch);
            try {
                if (!startLatch.await(5, TimeUnit.SECONDS)) {
                    log.error("Error waiting for RecorderEndpoint of Composite to start in session {}",
                            session.getSessionId());
                    throw new CloudMediaException(Code.RECORDING_START_ERROR_CODE,
                            "Couldn't initialize RecorderEndpoint of Composite");
                }
                log.info("RecorderEnpoint of Composite is now recording for session {}", session.getSessionId());
            } catch (InterruptedException e) {
                log.error("Exception while waiting for state change", e);
            }
        }

        log.info("Composite for session {} has now {} connected publishers", this.session.getSessionId(),
                this.composite.getChildren().size() - 1);
    }

    public void disconnectPublisherEndpoint(String streamId) {
        HubPort hubPort = this.hubPorts.remove(streamId);
        PublisherEndpoint publisherEndpoint = this.publisherEndpoints.remove(streamId);
        publisherEndpoint.disconnectFrom(hubPort);
        hubPort.release();
        log.info("Composite for session {} has now {} connected publishers", this.session.getSessionId(),
                this.composite.getChildren().size() - 1);
    }

    public void disconnectAllPublisherEndpoints() {
        this.publisherEndpoints.keySet().forEach(streamId -> {
            PublisherEndpoint endpoint = this.publisherEndpoints.get(streamId);
            HubPort hubPort = this.hubPorts.get(streamId);
            endpoint.disconnectFrom(hubPort);
            hubPort.release();
        });
        this.hubPorts.clear();
        this.publisherEndpoints.clear();
        this.composite.release();
    }

    public long getDuration() {
        return this.endTime - this.startTime;
    }

}