package org.ubonass.media.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.jsonrpc.internal.server.config.JsonRpcConfiguration;
import org.kurento.jsonrpc.server.JsonRpcConfigurer;
import org.kurento.jsonrpc.server.JsonRpcHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.cluster.ClusterSessionEvent;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.config.HttpHandshakeInterceptor;
import org.ubonass.media.server.core.MediaSessionManager;
import org.ubonass.media.server.core.SessionEventsHandler;
import org.ubonass.media.server.kurento.AutodiscoveryKurentoClientProvider;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.kurento.core.KurentoParticipantEndpointConfig;
import org.ubonass.media.server.kurento.core.KurentoSessionEventsHandler;
import org.ubonass.media.server.kurento.core.KurentoMediaSessionManager;
import org.ubonass.media.server.kurento.kms.FixedOneKmsManager;
import org.ubonass.media.server.recording.service.RecordingManager;
import org.ubonass.media.server.rpc.RpcHandler;
import org.ubonass.media.server.rpc.RpcNotificationService;
import org.ubonass.media.server.rpc.RpcRoomHandler;
import org.ubonass.media.server.utils.CommandExecutor;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;

@Import({JsonRpcConfiguration.class})
@SpringBootApplication
public class CloudMediaServerApplication implements JsonRpcConfigurer {

    private static final Logger logger =
            LoggerFactory.getLogger(CloudMediaServerApplication.class);
    @Autowired
    private Environment env;

    public static final String KMSS_URIS_PROPERTY = "kms.uris";

    @Bean
    @ConditionalOnMissingBean
    public KurentoClientProvider kmsManager() {

        JsonParser parser = new JsonParser();
        String uris = env.getProperty(KMSS_URIS_PROPERTY);
        JsonElement elem = parser.parse(uris);
        JsonArray kmsUris = elem.getAsJsonArray();
        List<String> kmsWsUris = JsonUtils.toStringList(kmsUris);

        if (kmsWsUris.isEmpty()) {
            throw new IllegalArgumentException(KMSS_URIS_PROPERTY +
                    " should contain at least one kms url");
        }

        String firstKmsWsUri = kmsWsUris.get(0);

        if (firstKmsWsUri.equals("autodiscovery")) {
            logger.info("Using autodiscovery rules to locate KMS on every pipeline");
            return new AutodiscoveryKurentoClientProvider();
        } else {
            logger.info("Configuring OpenVidu Server to use first of the following kmss: " + kmsWsUris);
            return new FixedOneKmsManager(firstKmsWsUri);
        }
    }

    @Bean
    public Config config() {
        //如果有集群管理中心，可以配置
        Config confg = null;
        try {
            confg = new XmlConfigBuilder(
                    CloudMediaServerApplication.class
                            .getResource("/ubonass-media-hazelcast.xml")
                            .openStream()).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return confg;
    }


    @Bean
    @ConditionalOnMissingBean
    public CloudMediaConfig cloudmediaConfig() {
        return new CloudMediaConfig();
    }

    @Bean
    public RpcHandler rpcHandler() {
        return new RpcRoomHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public MediaSessionManager sessionManager() {
        return new KurentoMediaSessionManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public RpcNotificationService rpcNotificationService() {
        return new RpcNotificationService();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionEventsHandler sessionEventsHandler() {
        return new KurentoSessionEventsHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public KurentoParticipantEndpointConfig kurentoEndpointConfig() {
        return new KurentoParticipantEndpointConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public RecordingManager recordingManager() {
        return new RecordingManager();
    }

    @ConditionalOnMissingBean
    @Bean
    public ClusterRpcService clusterRpcService() {
        return new ClusterRpcService(config());
    }

    @ConditionalOnMissingBean
    @Bean
    public ClusterSessionEvent clusterSessionEvent() {
        return new ClusterSessionEvent();
    }


    @Override
    public void registerJsonRpcHandlers(JsonRpcHandlerRegistry registry) {
        registry.addHandler(rpcHandler().withPingWatchdog(true)
                .withInterceptors(new HttpHandshakeInterceptor()), "/call");
    }

    private static String getContainerIp() throws IOException, InterruptedException {
        return CommandExecutor.execCommand("/bin/sh", "-c", "hostname -i | awk '{print $1}'");
    }

    public static void main(String[] args) {
        logger.info("Using /dev/urandom for secure random generation");
        System.setProperty("java.security.egd", "file:/dev/./urandom");
        SpringApplication.run(CloudMediaServerApplication.class, args);
    }


    @PostConstruct
    public void init() throws MalformedURLException, InterruptedException {
        CloudMediaConfig cloudmediaConfig = cloudmediaConfig();

        String publicUrl = cloudmediaConfig.getPublicUrl();
        String type = publicUrl;

        switch (publicUrl) {
            case "docker":
                try {
                    String containerIp = getContainerIp();
                    cloudmediaConfig.setWsUrl("wss://" + containerIp + ":" + cloudmediaConfig.getServerPort());
                } catch (Exception e) {
                    logger.error("Docker container IP was configured, but there was an error obtaining IP: "
                            + e.getClass().getName() + " " + e.getMessage());
                    logger.error("Fallback to local URL");
                    cloudmediaConfig.setWsUrl(null);
                }
                break;

            case "local":
                break;

            case "":
                break;

            default:

                type = "custom";

                if (publicUrl.startsWith("https://")) {
                    cloudmediaConfig.setWsUrl(publicUrl.replace("https://", "wss://"));
                } else if (publicUrl.startsWith("http://")) {
                    cloudmediaConfig.setWsUrl(publicUrl.replace("http://", "wss://"));
                }

                if (!cloudmediaConfig.getWsUrl().startsWith("wss://")) {
                    cloudmediaConfig.setWsUrl("wss://" + cloudmediaConfig.getWsUrl());
                }
        }

        if (cloudmediaConfig.getWsUrl() == null) {
            type = "local";
            cloudmediaConfig.setWsUrl("wss://localhost:" + cloudmediaConfig.getServerPort());
        }

        if (cloudmediaConfig.getWsUrl().endsWith("/")) {
            cloudmediaConfig.setWsUrl(
                    cloudmediaConfig.getWsUrl().substring(0,cloudmediaConfig.getWsUrl().length() - 1));
        }

        if (this.cloudmediaConfig().isRecordingModuleEnable()) {
            try {
                this.recordingManager().initializeRecordingManager();
            } catch (CloudMediaException e) {
                String finalErrorMessage = "";
                if (e.getCodeValue() == Code.DOCKER_NOT_FOUND.getValue()) {
                    finalErrorMessage = "Error connecting to Docker daemon. Enabling CloudMedia recording module requires Docker";
                } else if (e.getCodeValue() == Code.RECORDING_PATH_NOT_VALID.getValue()) {
                    finalErrorMessage = "Error initializing recording path \""
                            + this.cloudmediaConfig().getRecordingPath()
                            + "\" set with system property \"cloudmedia.recording.path\"";
                } else if (e.getCodeValue() == Code.RECORDING_FILE_EMPTY_ERROR.getValue()) {
                    finalErrorMessage = "Error initializing recording custom layouts path \""
                            + this.cloudmediaConfig().getRecordingCustomLayout()
                            + "\" set with system property \"openvidu.recording.custom-layout\"";
                }
                logger.error(finalErrorMessage + ". Shutting down CloudMedia Server");
                System.exit(1);
            }
        }

        String finalUrl = cloudmediaConfig.getWsUrl().replaceFirst("wss://", "https://").replaceFirst("ws://", "http://");
        cloudmediaConfig.setFinalUrl(finalUrl);
        logger.info("CloudMededia Server using " + type + " URL: [" + cloudmediaConfig.getWsUrl() + "]");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void whenReady() {
        final String NEW_LINE = System.lineSeparator();
        String str = NEW_LINE +
                NEW_LINE + "    ACCESS IP            " +
                NEW_LINE + "-------------------------" +
                NEW_LINE + cloudmediaConfig().getFinalUrl() +
                NEW_LINE + "-------------------------" +
                NEW_LINE;
        logger.info(str);
    }

}
