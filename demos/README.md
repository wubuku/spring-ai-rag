# Spring AI RAG — 示例项目

## 示例列表

| 示例 | 说明 | 难度 |
|------|------|------|
| [demo-basic-rag](demo-basic-rag/) | 最简单的 RAG 集成：5 分钟跑通问答 | ⭐ |
| [demo-domain-extension](demo-domain-extension/) | 领域扩展：三步添加新领域的智能问答 | ⭐⭐ |

## 快速开始

```bash
# 1. 安装框架到本地仓库
cd .. && mvn clean install -DskipTests

# 2. 进入示例目录
cd demos/demo-basic-rag

# 3. 配置环境变量
export DEEPSEEK_API_KEY=sk-your-key
export SILICONFLOW_API_KEY=sk-your-key

# 4. 启动
mvn spring-boot:run
```

## 更多示例（规划中）

- **demo-web-ui**: 带前端界面的完整 RAG 应用
- **demo-custom-advisor**: 如何编写自定义 Advisor
- **demo-ecommerce**: 电商领域扩展示例
