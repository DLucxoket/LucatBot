package org.dlucxoket.lucatbot

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["storage.type=json", "mainloop.auto-start=false"])
/** Spring 容器启动冒烟测试（固定 json 后端，测试不依赖 MySQL；关闭主循环就绪自启，
 *  避免测试上下文真起循环打 LLM——MySQL 路径见 MySqlStorageSmokeTest） */
class LucatBotApplicationTests {

    @Test
    fun contextLoads() {
    }

}
