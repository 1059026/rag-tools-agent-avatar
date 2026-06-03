此文件夹用于放置 RAG 知识库文档。

支持的格式：.txt / .md / .pdf / .docx

使用方式：
1. 将XX行业相关文档放入此文件夹
2. 修改 src/main/java/com/example/demo/config/AiMemoryConfig.java 中的加载路径指向此文件夹
3. 启动 Java 后端即可自动索引
