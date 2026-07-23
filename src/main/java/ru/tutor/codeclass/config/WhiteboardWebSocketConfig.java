package ru.tutor.codeclass.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import ru.tutor.codeclass.web.WhiteboardWebSocketHandler;
import java.util.*;

@Configuration
@EnableWebSocket
public class WhiteboardWebSocketConfig implements WebSocketConfigurer {
    private final WhiteboardWebSocketHandler handler;
    private final String[] allowedOrigins;

    public WhiteboardWebSocketConfig(WhiteboardWebSocketHandler handler,
                                     @Value("${app.websocket.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(handler, "/ws/boards/{publicId}")
                .addInterceptors(new BoardIdInterceptor());
        if (allowedOrigins.length > 0) registration.setAllowedOrigins(allowedOrigins);
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(128 * 1024);
        container.setMaxBinaryMessageBufferSize(128 * 1024);
        return container;
    }

    static class BoardIdInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String path = request.getURI().getPath();
            String value = path.substring(path.lastIndexOf('/') + 1);
            try { attributes.put("boardId", UUID.fromString(value)); return true; }
            catch (IllegalArgumentException ex) { return false; }
        }
        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                             WebSocketHandler wsHandler, Exception exception) {}
    }
}
