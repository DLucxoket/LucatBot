package org.dlucxoket.lucatbot.adapter.onebot.config

import com.mikuac.shiro.adapter.WebSocketServerHandler
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.handler.WebSocketSessionDecorator
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Shiro 2.5.0 + Spring Boot 4 / Tomcat 11 WebSocket 兼容性修复
 *
 * 问题根因：
 * - Tomcat 11 在 WebSocket 升级完成后立即回收 request 对象
 * - Spring 的 `session.getHandshakeHeaders()` 底层是 `ServletRequestHeadersAdapter`，直接引用 request
 * - Shiro 的 `ConnectionUtils.parseSelfId` 在 `afterConnectionEstablished` 时调 `getHandshakeHeaders().getFirst("x-self-id")`
 * - 此时 request 已被回收 → `IllegalStateException: The request object has been recycled`
 *
 * 修复方案：
 * 1. [SelfIdCaptureInterceptor] 握手阶段提取 self_id（request 尚未回收）
 * 2. [CachedHandshakeSessionDecorator] 装饰 WebSocketSession，`getHandshakeHeaders()` 返回**缓存副本**
 * 3. Shiro 后续读 handshake headers 时拿到的是缓存数据，不再触发 request 访问
 */
@Configuration
@EnableWebSocket
@Order(Ordered.HIGHEST_PRECEDENCE)
class ShiroWebSocketConfig(
    private val shiroHandler: WebSocketServerHandler
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val wrapped = CachingHandlerDecorator(shiroHandler)
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
            // 从 URL query 提取 self_id
            val query = request.uri.query ?: ""
            val querySelfId = query.split("&")
                .firstOrNull { it.startsWith("self_id=") || it.startsWith("self-id=") }
                ?.substringAfter("=")
            if (querySelfId != null) {
                attributes["shiro_self_id"] = querySelfId
            }

            // 从 header 提取 self_id
            val headerSelfId = request.headers.getFirst("x-self-id") ?: request.headers.getFirst("self_id")
            if (headerSelfId != null) {
                attributes["shiro_self_id"] = headerSelfId
            }

            // 缓存完整的 handshake headers（关键修复！）
            val cachedHeaders = HttpHeaders()
            cachedHeaders.addAll(request.headers)
            attributes["shiro_cached_handshake_headers"] = cachedHeaders

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
     * WebSocketHandler 装饰器：用缓存 session 替换原始 session
     *
     * 在 `afterConnectionEstablished` 前将 session 替换为 [CachedHandshakeSessionDecorator]，
     * 使 Shiro 读 `getHandshakeHeaders()` 时返回缓存副本。
     */
    class CachingHandlerDecorator(
        delegate: WebSocketHandler
    ) : WebSocketHandlerDecorator(delegate) {

        override fun afterConnectionEstablished(session: WebSocketSession) {
            val cachedSession = CachedHandshakeSessionDecorator(session)
            try {
                super.afterConnectionEstablished(cachedSession)
            } catch (e: IllegalStateException) {
                // Tomcat 11 request recycled 兼容性异常
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

    /**
     * WebSocketSession 装饰器：缓存 handshake headers
     *
     * 首次调用 `getHandshakeHeaders()` 时从原始 session 复制 headers 并缓存，
     * 后续调用返回缓存副本，不再访问 request 对象。
     */
    class CachedHandshakeSessionDecorator(
        delegate: WebSocketSession
    ) : WebSocketSessionDecorator(delegate) {

        private var cachedHeaders: HttpHeaders? = null

        override fun getHandshakeHeaders(): HttpHeaders {
            if (cachedHeaders == null) {
                // 先从握手拦截器缓存获取（最可靠）
                val attrHeaders = attributes["shiro_cached_handshake_headers"]
                if (attrHeaders is HttpHeaders) {
                    cachedHeaders = attrHeaders
                } else {
                    // 回退：从原始 session 复制（可能抛异常）
                    try {
                        val copy = HttpHeaders()
                        copy.addAll(delegate.handshakeHeaders)
                        cachedHeaders = copy
                    } catch (e: Exception) {
                        cachedHeaders = HttpHeaders()
                    }
                }
            }
            return cachedHeaders!!
        }
    }
}
