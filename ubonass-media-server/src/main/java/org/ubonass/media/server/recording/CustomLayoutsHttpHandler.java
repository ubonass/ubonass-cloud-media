
package org.ubonass.media.server.recording;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.ubonass.media.server.config.CloudMediaConfig;

@Configuration
@ConditionalOnProperty(name = "openvidu.recording", havingValue = "true")
public class CustomLayoutsHttpHandler extends WebMvcConfigurerAdapter {

    @Autowired
    CloudMediaConfig cloudMediaConfig;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String customLayoutsPath = cloudMediaConfig.getRecordingCustomLayout();
        customLayoutsPath = customLayoutsPath.endsWith("/") ? customLayoutsPath : customLayoutsPath + "/";
        cloudMediaConfig.setRecordingCustomLayout(customLayoutsPath);

        registry.addResourceHandler("/layouts/custom/**").addResourceLocations("file:" + customLayoutsPath);
    }

}