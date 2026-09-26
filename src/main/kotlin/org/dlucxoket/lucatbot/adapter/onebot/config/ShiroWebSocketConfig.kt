package org.dlucxoket.lucatbot.adapter.onebot.config

import com.mikuac.shiro.adapter.WebSocketServerHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ExceptionWebSocketHandlerDecorator

/**
 * Shiro WebSocket 兼容性修复配置
 *
 * 解决 Shiro 2.5.0 + Spring Boot 4 / Tomcat 11 的 `request recycled` 兼容性问题。
 *
 * 修复方案：
 * 1. 禁用 Shiro 自动注册（application.yaml: shiro.ws.server.enable: false）
 * 2. 自行注册 WebSocket 端点，握手阶段通过 HandshakeInterceptor 提取 self_id
 * 3. 包装 Shiro handler，捕获 parseSelfId 的 IllegalStateException
 */
@Configuration
@EnableWebSocket
class ShiroWebSocketConfig(
    private val shiroHandler: WebSocketServerHandler
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val wrapped = SelfIdFixHandlerDecorator(shiroHandler)
        registry.addHandler(wrapped, "/ws/shiro")
            .addInterceptors(SelfIdCaptureInterceptor())
            .setAllowedOrigins("*")
    }

    /**
     * 握手拦截器：在 request 被回收前提取 self_id 和 token
     */
    class SelfIdCaptureInterceptor : HandshakeInterceptor {
        override fun beforeHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            attributes: MutableMap<String, Any>
        ): Boolean {
            // 从 URL query 提取 self_id
            val uri = request.uri
            val query = uri.query ?: ""
            val selfId = query.split("&")
                .firstOrNull { it.startsWith("self_id=") || it.startsWith("self-id=") }
                ?.substringAfter("=")

            if (selfId != null) {
                attributes["self_id"] = selfId
            }

            // 从 header 提取 self_id（NapCat 会发 x-self-id header）
            val headers = request.headers
            val headerSelfId = headers.getFirst("x-self-id") ?: headers.getFirst("self_id")
            if (headerSelfId != null) {
                attributes["self_id"] = headerSelfId
            }

            return true
        }

        override fun afterHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            exception: Exception?
        ) {
            // no-op
        }
    }

    /**
     * WebSocketHandler 装饰器：捕获 Shiro parseSelfId 的异常
     *
     * 将 Shiro handler 的异常吞掉，避免整个 WebSocket 会话被关闭。
     */
    class SelfIdFixHandlerDecorator(
        delegate: WebSocketHandler
    ) : WebSocketHandlerDecorator(delegate) {

        override fun afterConnectionEstablished(session: WebSocketSession) {
            try {
                super.afterConnectionEstablished(session)
            } catch (e: IllegalStateException) {
                // 兼容性异常：request recycled，但 self_id 已在握手阶段捕获
                // Shiro 后续的 handleTextMessage 会从 session attributes 读 self_id
            }
        }

        override fun handleMessage(session: WebSocketSession, message: org.springframework.web.socket.WebSocketMessage<*>) {
            try {
                super.handleMessage(session, message)
            } catch (e: IllegalStateException) {
                // 同样的兼容性异常，忽略
            }
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            try {
                super.afterConnectionClosed(session, closeStatus)
            } catch (e: IllegalStateException) {
                // 忽略
            }
        }
    }
}
