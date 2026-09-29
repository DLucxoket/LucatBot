package org.dlucxoket.lucatbot.bot.tool

import io.github.classgraph.ClassGraph
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
 * `objectInstance`，class 用无参构造只创建一次；缓存 KClass、各调用点
 * 各自 `createInstance()` 的做法会让 bindBot/bindServices 落在被丢弃的
 * 一次性实例上（OOP 审查严重问题，已修）。
 *
 * 职责：提供全部工具实例（ToolCatalog 组目录/调用、Binder 绑定共用）。
 * 按名查找由调用方在列表上做（唯一消费方 ToolCatalog 只有一个），
 * 不再维护冗余的名称索引 API。
 */
object ToolDiscovery {

    private val log = org.slf4j.LoggerFactory.getLogger(ToolDiscovery::class.java)

    /** 全部可用工具实例（去重，保持扫描顺序），扫描期构建一次 */
    private val toolList: List<Tool<*, *>> by lazy { scanTools() }

    /**
     * 获取全部工具实例（目录/绑定共用）
     */
    fun getTools(): List<Tool<*, *>> = toolList

    /**
     * 扫描所有 Tool 子类（一次性）
     */
    private fun scanTools(): List<Tool<*, *>> {
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

        // 重名检测（name 与类简单名都参与，冲突告警后保留先到者）
        val seen = HashMap<String, Tool<*, *>>()
        instances.forEach { inst ->
            listOfNotNull(inst.name, inst::class.simpleName).forEach { key ->
                val prev = seen.put(key, inst)
                if (prev != null && prev !== inst) {
                    log.warn(
                        "Tool 名称冲突 key={}：{} 与 {} 重名，目录以先扫描到的为准",
                        key, prev::class.simpleName, inst::class.simpleName
                    )
                }
            }
        }
        log.info("Tool 发现完成：{} 个", instances.size)
        return instances.toList()
    }
}
