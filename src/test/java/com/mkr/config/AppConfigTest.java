package com.mkr.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置加载：YAML 解析、${VAR} 替换、permissions.yml 层级合并、provider 默认值。 */
class AppConfigTest {

    @Test
    void envSubstitutionWithDefault(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("config.yaml"), """
                llm:
                  provider: zhipu
                  api-key: ${MISSING_VAR:-fallback-key}
                """);
        AppConfig cfg = AppConfig.load(tmp, tmp.resolve("config.yaml"));
        assertEquals("fallback-key", cfg.llm.apiKey);
        assertEquals("zhipu", cfg.llm.provider);
    }

    @Test
    void providerDefaultsApplied(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("config.yaml"), "llm: {provider: zhipu, api-key: k}");
        AppConfig cfg = AppConfig.load(tmp, tmp.resolve("config.yaml"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4", cfg.llm.baseUrl);
        assertEquals("glm-4-plus", cfg.llm.model);
    }

    @Test
    void dotEnvFeedsSubstitution(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "MY_KEY=from-dotenv");
        Files.writeString(tmp.resolve("config.yaml"), "llm: {api-key: ${MY_KEY}}");
        AppConfig cfg = AppConfig.load(tmp, tmp.resolve("config.yaml"));
        assertEquals("from-dotenv", cfg.llm.apiKey);
    }

    @Test
    void permissionFilesMergeDenyUnion(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("home");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Files.createDirectories(home.resolve(".mkr"));
            Files.writeString(home.resolve(".mkr/permissions.yml"), """
                    allow: ["~/global-ws/**"]
                    deny: ["~/.ssh/**"]
                    """);
            Files.writeString(tmp.resolve("config.yaml"), """
                    permissions:
                      allow: ["./workspace/**"]
                      deny: [".env"]
                    """);
            Files.createDirectories(tmp.resolve("config"));
            Files.writeString(tmp.resolve("config/permissions.yml"), """
                    ask: ["./pom.xml"]
                    deny: ["**/*.pem"]
                    """);
            AppConfig cfg = AppConfig.load(tmp, tmp.resolve("config.yaml"));
            // 项目级 allow 覆盖全局
            assertEquals(List.of("./workspace/**"), cfg.permissions.allow);
            assertEquals(List.of("./pom.xml"), cfg.permissions.ask);
            // deny 三层并集
            assertEquals(3, cfg.permissions.deny.size());
            assertTrue(cfg.permissions.deny.contains(".env"));
            assertTrue(cfg.permissions.deny.contains("**/*.pem"));
            assertTrue(cfg.permissions.deny.contains("~/.ssh/**"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void fromMapParsesPricesAndFeatures(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("config.yaml"), """
                obs:
                  prices:
                    glm-4-plus: [0.7, 0.7]
                features:
                  sidecar: false
                agent:
                  max-iterations: 12
                """);
        AppConfig cfg = AppConfig.load(tmp, tmp.resolve("config.yaml"));
        assertEquals(0.7, cfg.obs.prices.get("glm-4-plus")[1]);
        assertEquals(false, cfg.features.get("sidecar"));
        assertEquals(12, cfg.agent.maxIterations);
        // 未覆盖的特性保持默认
        assertEquals(Map.ofEntries(Map.entry("compression", true)).get("compression"),
                cfg.features.get("compression"));
    }
}
