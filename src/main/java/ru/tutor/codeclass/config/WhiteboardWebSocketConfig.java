package ru.tutor.codeclass.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
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
    public WhiteboardWebSocketConfig(WhiteboardWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/boards/{publicId}")
                .addInterceptors(new BoardIdInterceptor());
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
