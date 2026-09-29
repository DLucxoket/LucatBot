package org.dlucxoket.lucatbot.bot.agent

import io.github.classgraph.ClassGraph
import org.dlucxoket.lucatbot.bot.tool.Tool
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf

/**
 * Tool 自动发现器
 *
 * 通过 ClassGraph 扫描 classpath，一次性构建**工具实例索引**并缓存。
 * 线程安全（lazy 初始化，结果不可变）。
 *
 * 关键约定：发现/绑定/调用共享**同一个实例**——object 取
 * `objectInstance`，class 用无参构造只创建一次。此前缓存 KClass、
 * 各调用点各自 `createInstance()` 的做法会让 bindBot/bindServices
 * 落在被丢弃的一次性实例上（OOP 审查严重问题 #2），现已统一。
 *
 * 职责：
 * - 按名称（name 与类简单名双 key）查找工具实例
 * - 提供全部工具列表（供 ToolCatalog 组目录、Binder 绑定）
 */
object AgentDiscovery {

    private val log = org.slf4j.LoggerFactory.getLogger(AgentDiscovery::class.java)

    /** 工具实例索引（name / 类简单名 → 实例），扫描期构建一次 */
    private val toolIndex: Map<String, Tool<*, *>> by lazy { buildIndex() }

    /** 全部可用工具实例（去重，保持扫描顺序）——命名避开 getTools() 的 JVM 签名 */
    private val toolList: List<Tool<*, *>> by lazy { toolIndex.values.distinct() }

    /**
     * 获取全部工具实例（目录/绑定共用）
     */
    fun getTools(): List<Tool<*, *>> = toolList

    /**
     * 按名称查找工具实例（name 或类简单名，精确匹配；不存在返回 null）
     */
    fun findTool(name: String): Tool<*, *>? = toolIndex[name]

    /**
     * 扫描所有 Tool 子类并建索引（一次性）
     */
    private fun buildIndex(): Map<String, Tool<*, *>> {
        val instances = LinkedHashSet<Tool<*, *>>()
        ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Tool::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .forEach { cls ->
                        val rawKClass = cls.loadClass().kotlin
                        if (!rawKClass.isSubclassOf(Tool::class)) return@forEach
                        @Suppress("UNCHECKED_CAST")
                        val kClass = rawKClass as KClass<out Tool<*, *>>
                        // 实例化失败（如带必需构造参数的测试 Fake 子类）则跳过，不进发现缓存
                        val instance = kClass.objectInstance
                            ?: runCatching { kClass.createInstance() }.getOrNull()
                        if (instance == null) {
                            log.debug("跳过无法实例化的 Tool 子类: {}", cls.name)
                            return@forEach
                        }
                        instances += instance
                    }
            }

        val index = LinkedHashMap<String, Tool<*, *>>()
        instances.forEach { inst ->
            listOfNotNull(inst.name, inst::class.simpleName).forEach { key ->
                val prev = index.put(key, inst)
                if (prev != null && prev !== inst) {
                    log.warn(
                        "Tool 名称冲突 key={}：{} 与 {} 重名，后者覆盖",
                        key, prev::class.simpleName, inst::class.simpleName
                    )
                }
            }
        }
        log.info("Tool 发现完成：{} 个", instances.size)
        return index
    }
}
