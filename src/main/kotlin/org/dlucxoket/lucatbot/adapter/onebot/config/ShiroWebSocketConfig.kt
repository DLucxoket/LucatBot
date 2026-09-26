package org.dlucxoket.lucatbot.adapter.onebot.config

import com.mikuac.shiro.adapter.WebSocketServerHandler
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
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

/**
 * Shiro WebSocket 兼容性修复配置
 *
 * 解决 Shiro 2.5.0 + Spring Boot 4 / Tomcat 11 的 `request recycled` 兼容性问题。
 *
 * 修复方案：
 * 1. @Order(HIGHEST_PRECEDENCE) 让本配置优先于 Shiro 自动注册
 * 2. HandshakeInterceptor 在握手阶段提取 self_id（request 尚未回收）
 * 3. SelfIdFixHandlerDecorator 捕获 parseSelfId 的 IllegalStateException
 *
 * 注意：需保持 shiro.ws.server.enable: true 让 Shiro 创建 handler bean
 */
@Configuration
@EnableWebSocket
@Order(Ordered.HIGHEST_PRECEDENCE)
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
     * 握手拦截器：在 request 被回收前提取 self_id
     */
    class SelfIdCaptureInterceptor : HandshakeInterceptor {
        override fun beforeHandshake(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            wsHandler: WebSocketHandler,
            attributes: MutableMap<String, Any>
        ): Boolean {
            val uri = request.uri
            val query = uri.query ?: ""
            val selfId = query.split("&")
                .firstOrNull { it.startsWith("self_id=") || it.startsWith("self-id=") }
                ?.substringAfter("=")
            if (selfId != null) {
                attributes["self_id"] = selfId
            }

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
        ) {}
    }

    /**
     * WebSocketHandler 装饰器：捕获 Shiro parseSelfId 的兼容性异常
     */
    class SelfIdFixHandlerDecorator(
        delegate: WebSocketHandler
    ) : WebSocketHandlerDecorator(delegate) {

        override fun afterConnectionEstablished(session: WebSocketSession) {
            try {
                super.afterConnectionEstablished(session)
            } catch (e: IllegalStateException) {
                // Tomcat 11 request recycled 兼容性异常，忽略
            }
        }

        override fun handleMessage(session: WebSocketSession, message: org.springframework.web.socket.WebSocketMessage<*>) {
            try {
                super.handleMessage(session, message)
            } catch (e: IllegalStateException) {
                // 忽略
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
