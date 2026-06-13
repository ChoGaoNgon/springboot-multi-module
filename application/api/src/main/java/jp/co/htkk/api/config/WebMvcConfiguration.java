package jp.co.htkk.api.config;

import jp.co.htkk.framework.component.MessageService;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Locale;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Bean
    public MessageService messageService(final MessageSource messageSource) {
        return new MessageService(messageSource, Locale.JAPANESE);
    }
}
