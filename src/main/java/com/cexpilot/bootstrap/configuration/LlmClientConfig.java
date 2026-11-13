package com.cexpilot.bootstrap.configuration;

import com.cexpilot.platform.llm.LlmConfig;

import com.cexpilot.platform.llm.LlmClient;
import com.cexpilot.platform.llm.OpenAiCompatibleClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 注册两套并存的 LLM 客户端：normal（普通模型）与 flagship（旗舰模型）。
 * normal 标为 @Primary，现有按类型注入 LlmClient 的流程默认走普通模型；
 * DagPlanner 首次使用 normal，校验失败后的修复使用 flagship；Answer 始终使用 normal。
 */
@Configuration
public class LlmClientConfig {

    public static final String NORMAL_LLM_CLIENT = LlmClient.NORMAL_LLM_CLIENT;
    public static final String FLAGSHIP_LLM_CLIENT = LlmClient.FLAGSHIP_LLM_CLIENT;

    /** 普通模型客户端：路由、规划首发与回答都使用；@Primary 保证按类型注入时默认选中。 */
    @Bean(NORMAL_LLM_CLIENT)
    @Primary
    public LlmClient normalLlmClient(LlmConfig config) {
        return new OpenAiCompatibleClient(config.getNormal(), config.getTemperature(), config.getSeed(),
                config.getNormal().isPooling());
    }

    /** 旗舰模型客户端：仅 planner 校验失败后的修复重试使用。 */
    @Bean(FLAGSHIP_LLM_CLIENT)
    public LlmClient flagshipLlmClient(LlmConfig config) {
        return new OpenAiCompatibleClient(config.getFlagship(), config.getTemperature(), config.getSeed(),
                config.getFlagship().isPooling());
    }
}
