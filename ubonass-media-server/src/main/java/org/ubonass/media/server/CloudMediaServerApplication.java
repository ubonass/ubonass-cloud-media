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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.config.HttpHandshakeInterceptor;
import org.ubonass.media.server.core.SessionEventsHandler;
import org.ubonass.media.server.core.SessionManager;
import org.ubonass.media.server.kurento.AutodiscoveryKurentoClientProvider;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.kurento.core.KurentoParticipantEndpointConfig;
import org.ubonass.media.server.kurento.core.KurentoSessionEventsHandler;
import org.ubonass.media.server.kurento.core.KurentoSessionManager;
import org.ubonass.media.server.kurento.kms.FixedOneKmsManager;
import org.ubonass.media.server.rpc.RpcCallHandler;
import org.ubonass.media.server.rpc.RpcHandler;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.io.IOException;
import java.util.Arrays;
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
    public CloudMediaConfig cloudMediaConfig() {
        return new CloudMediaConfig();
    }

    @Bean
    public RpcHandler rpcHandler() {
        return new RpcHandler();
    }

    @Bean
    public RpcHandler callRpcHandler() {
        return new RpcCallHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager() {
        return new KurentoSessionManager();
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


    @ConditionalOnMissingBean
    @Bean
    public ClusterRpcService clusterRpcService() {
        return new ClusterRpcService(config()/*,sessionManager()*/);
    }

    @Override
    public void registerJsonRpcHandlers(JsonRpcHandlerRegistry registry) {
        /*registry.addHandler(rpcHandler().withPingWatchdog(true)
                .withInterceptors(new HttpHandshakeInterceptor()), "/media");*/
        registry.addHandler(callRpcHandler().withPingWatchdog(true)
                .withInterceptors(new HttpHandshakeInterceptor()), "/call");
    }

    public static void main(String[] args) {
        SpringApplication.run(CloudMediaServerApplication.class, args);
    }


}
