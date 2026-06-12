package jp.co.htkk.api.interceptor;

import jp.co.htkk.framework.component.MessageService;

import jp.co.htkk.framework.exception.type.ServiceException;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Data
public class AuthorizationInterceptor implements HandlerInterceptor {

    @Autowired
    @Lazy
    MessageService messageService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String servletPath = request.getServletPath();

        String httpMethod = request.getMethod();
        if (StringUtils.isNotBlank(servletPath)) {
            // Todo
            return true;
        }
        throw new ServiceException(HttpStatus.FORBIDDEN, messageService.getMessage("error.message.common.authentication.unauthorized"));
    }
}
