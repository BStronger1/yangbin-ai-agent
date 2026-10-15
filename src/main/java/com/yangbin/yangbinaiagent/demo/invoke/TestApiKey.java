package com.yangbin.yangbinaiagent.demo.invoke;

/**
 * 仅用于测试获取 API Key
 */
public interface TestApiKey {

    // 修改为你的 API Key
    String API_KEY = System.getenv("DASHSCOPE_API_KEY");
}
