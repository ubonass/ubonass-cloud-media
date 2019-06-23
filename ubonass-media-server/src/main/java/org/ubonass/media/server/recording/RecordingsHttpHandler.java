
package org.ubonass.media.server.recording;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.ubonass.media.server.config.CloudMediaConfig;

@Configuration
public class RecordingsHttpHandler extends WebMvcConfigurerAdapter {

    @Autowired
    private CloudMediaConfig cloudMediaConfig;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        String recordingsPath = cloudMediaConfig.getRecordingPath();
        recordingsPath = recordingsPath.endsWith("/") ? recordingsPath : recordingsPath + "/";

        cloudMediaConfig.setRecordingPath(recordingsPath);

        registry.addResourceHandler("/recordings/**").addResourceLocations("file:" + recordingsPath);
    }

}
