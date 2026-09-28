package org.dlucxoket.lucatbot.bot.agent

import io.github.classgraph.ClassGraph
import org.dlucxoket.lucatbot.bot.tool.Tool
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf

/**
 * Tool 自动发现器
 *
 * 通过 ClassGraph 扫描 classpath，缓存发现结果，避免每次调用重复扫描。
 * 线程安全（ConcurrentHashMap + lazy 初始化）。
 *
 * 职责：
 * - 缓存 Tool 子类列表
 * - 按名称查找并实例化
 */
object AgentDiscovery {

    private val log = org.slf4j.LoggerFactory.getLogger(AgentDiscovery::class.java)

    /** Tool 类缓存（名称 → KClass） */
    private val toolCache: MutableMap<String, KClass<out Tool<*, *>>> by lazy {
        scanTools()
    }

    /**
     * 获取所有已发现的 Tool 类
     */
    fun getToolClasses(): List<KClass<out Tool<*, *>>> {
        return toolCache.values.toList()
    }

    /**
     * 按名称查找 Tool 并实例化
     */
    fun findTool(name: String): Tool<*, *>? {
        val kClass = toolCache[name] ?: return null
        return kClass.objectInstance ?: kClass.createInstance()
    }

    /**
     * 扫描所有 Tool 子类（一次性）
     */
    private fun scanTools(): MutableMap<String, KClass<out Tool<*, *>>> {
        val cache = ConcurrentHashMap<String, KClass<out Tool<*, *>>>()
        ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Tool::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .forEach { cls ->
                        val kClass = cls.loadClass().kotlin
                        if (kClass.isSubclassOf(Tool::class)) {
                            @Suppress("UNCHECKED_CAST")
                            val typed = kClass as KClass<out Tool<*, *>>
                            // 用 name 属性和简单名做双重 key；
                            // 实例化失败（如带必需构造参数的测试 Fake 子类）则跳过，不进发现缓存
                            val instance = kClass.objectInstance
                                ?: runCatching { kClass.createInstance() }.getOrNull()
                            if (instance == null) {
                                log.debug("跳过无法实例化的 Tool 子类: {}", cls.name)
                                return@forEach
                            }
                            cache[instance.name] = typed
                            cache[kClass.simpleName ?: cls.name] = typed
                        }
                    }
            }
        return cache
    }
}
