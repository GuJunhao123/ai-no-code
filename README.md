# AI No-Code 应用生成平台

基于 **Spring Boot + LangChain4j + Vue 3** 的全栈学习实践项目。用自然语言描述需求，由 AI 选择生成方式，完成代码生成、对话修改、在线预览、源码下载和静态发布。

本仓库包含从 0 到可运行的完整项目代码和运行指南：数据库脚本、Java 后端、Vue 管理前端、提示词、Nginx 示例与开发笔记。启动需要自行准备 MySQL、Redis 和可用的模型服务，不是无需配置的离线程序。

## 功能

| 模块 | 当前实现 |
| --- | --- |
| 用户与权限 | 注册、登录、Redis Session、用户管理与管理员权限校验 |
| 应用管理 | 创建、编辑、查询、删除、精选应用与管理页面 |
| AI 智能路由 | 根据初始需求选择 HTML、多文件或 Vue 工程生成 |
| 代码生成 | HTML / CSS / JavaScript 解析保存；Vue 工程通过文件工具生成与修改 |
| 流式交互 | SSE 输出；TokenStream 回调转换为 Flux，区分 AI 文本和工具消息 |
| 对话记忆 | MySQL 保存聊天历史，时间游标查询；Redis 保存窗口记忆并加载历史 |
| 在线预览 | Spring 静态资源接口，前端 iframe 展示生成结果 |
| 可视化修改 | 选中页面元素，将元素上下文与修改要求交给 AI |
| 构建与发布 | 后端调用 npm 构建 Vue 工程，复制静态文件，由 Nginx 提供访问 |
| 源码下载 | 过滤文件后打包 ZIP 下载 |
| 系统优化 | Caffeine AI Service 缓存、Redis 精选应用缓存、Redisson 限流、输入护轨与工具调用次数限制 |

**实现边界：** 笔记中的截图上传 COS、完整 LangGraph4j 工作流、ARMS / Prometheus / Grafana、Nacos / Dubbo / Higress 和微服务拆分属于扩展学习内容，不能视为当前代码已落地。LangGraph4j 当前仅引入依赖；输出护轨类存在，但生成链路中的接入已注释。

## 技术栈

- 后端：Java 21、Spring Boot 3.5.4、MyBatis-Flex 1.11.1、MySQL、Hutool、Knife4j。
- AI：LangChain4j OpenAI / Reactor 1.18.1-beta28、Redis community 1.4.0-beta10；OpenAI 兼容模型接口。
- 缓存与会话：Redis、Spring Session、Caffeine、Redisson 3.50.0。
- 前端：Vue 3.5、TypeScript 5.8、Vite 7、Ant Design Vue、Pinia、Vue Router、Axios。
- 发布：本地文件系统、Node.js / npm、Nginx。

## 项目结构

```text
ai-no-code/
├── README.md
├── notes/
│   ├── n1.md                         # AI 接入、应用、预览、记忆、Vue 生成
│   └── n2.md                         # 路由、可视化修改、优化及扩展方向
└── ai-creativity-s/
    ├── pom.xml
    ├── mvnw / mvnw.cmd
    ├── sql/create_table.sql
    ├── nginx.conf
    ├── src/main/java/com/yupi/yuaicodemother/
    │   ├── ai/                       # AI Service、路由、工具、消息、护轨
    │   ├── core/                     # 门面、解析、保存、流处理、Vue 构建
    │   ├── controller/               # 用户、应用、聊天、静态资源接口
    │   ├── service/                  # 业务实现
    │   ├── model/                    # 实体、DTO、VO、枚举
    │   ├── config/                   # 模型、Redis、跨域等配置
    │   └── ratelimter/               # 分布式限流（保留原目录拼写）
    ├── src/main/resources/
    │   ├── application.yml
    │   ├── mapper/
    │   └── prompt/
    ├── src/test/
    └── yu-ai-code-mother-frontend/
```

运行时在后端工作目录下生成 `tmp/code_output` 与 `tmp/code_deploy`，不随源码提交。

## 学习与实现路线

结合 [第一阶段笔记](notes/n1.md) 和 [扩展阶段笔记](notes/n2.md)，建议按以下顺序阅读：

1. **基础工程**：搭建前后端、公共响应与异常处理、数据库、登录权限。
2. **声明式 AI 服务**：区分普通 / 流式模型配置，通过接口与提示词调用模型。
3. **生成与保存**：用门面封装生成入口，按 HTML / 多文件类型解析输出并保存。
4. **应用闭环**：创建应用、对话生成、Spring 预览和 Nginx 静态发布。
5. **对话历史**：MySQL 持久化与游标查询，Redis MessageWindowChatMemory，历史回填。
6. **Vue 工程**：文件工具调用，TokenStream → Flux，结构化工具消息，npm 构建。
7. **交互扩展**：AI 路由、源码下载、页面元素选择与增量修改。
8. **系统优化**：按需获取 prototype 模型、服务实例缓存、精选缓存、限流与输入护轨。

## 从 0 到本地运行

以下以 Windows PowerShell 为例。命令中的密码和密钥均需替换，不要提交真实值。

### 1. 准备环境与代码

| 依赖 | 要求 |
| --- | --- |
| JDK | 21，确认 `JAVA_HOME` 指向 JDK 21 |
| Maven | 可使用仓库 Wrapper（Maven 3.9.11），首次运行需联网 |
| Node.js | 建议 22.x，至少 22.12；npm 需位于后端进程的 PATH 中 |
| MySQL | 建议 8.x |
| Redis | 建议 6.2+，用于登录态、对话记忆、缓存和限流 |
| 模型服务 | OpenAI 兼容接口；Vue 生成模型需支持流式输出与工具调用 |
| Nginx | 仅静态发布时需要，普通开发预览可先不配置 |

```powershell
git clone git@github.com:GuJunhao123/ai-no-code.git
Set-Location ai-no-code/ai-creativity-s
java -version
node --version
npm --version
```

### 2. 初始化数据库

启动 MySQL，使用客户端连接：

```powershell
mysql -h localhost -P 3306 -u root -p
```

在 MySQL 客户端中执行（路径替换为实际克隆路径）：

```sql
SOURCE D:/Code_Program/Program/ai-no-code/ai-creativity-s/sql/create_table.sql;
```

脚本创建 `yu_ai_code_mother` 库与 `user`、`app`、`chat_history` 表，没有预置管理员和演示应用。请先通过前端注册账号；如需测试管理功能，由数据库管理者将该账号的 `userRole` 设置为 `admin`，然后退出并重新登录。不要开放公共提权接口。

### 3. 启动 Redis

启动本机 Redis，并确认可访问：

```powershell
redis-cli -h localhost -p 6379 ping
```

正常响应为 `PONG`。有密码时按自己的客户端认证方式连接，不要把密码写进仓库。

当前自定义 Redis 聊天记忆配置不单独传递 database，统一使用 **DB 0**。更改 Spring Redis database 时，需要同步调整聊天记忆实现，不能只改一处。

### 4. 配置后端环境变量

在启动后端的同一个 PowerShell 窗口中设置：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/yu_ai_code_mother'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<你的数据库密码>'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:REDIS_PASSWORD = ''
$env:AI_BASE_URL = 'https://api-inference.modelscope.cn/v1'
$env:AI_API_KEY = '<你自己的模型服务密钥>'
$env:AI_MODEL = '<账号可用的代码生成模型 ID>'
$env:AI_REASONING_MODEL = '<支持流式和工具调用的模型 ID>'
$env:AI_ROUTING_MODEL = '<账号可用的路由模型 ID>'
$env:AI_MAX_TOKENS = '8192'
```

- 默认接口为 ModelScope；切换服务商时修改 URL、模型 ID 与 Key。OpenAI 兼容接口通常需要 `/v1` 路径。
- 配置文件中的默认模型名仅为示例，不保证当前账号可调用；配额、上下文和输出上限以服务商为准。
- `DB_PASSWORD`、`AI_API_KEY` 必须提供；Redis 无密码时保持空值。
- 普通、流式、推理流式、路由配置共用 `AI_API_KEY`，模型可分别指定。
- 默认关闭模型请求和响应日志，避免记录提示词与生成内容。
- Spring Boot 不会自动读取普通 `.env` 文件；请使用进程环境变量或自行配置 IDE 启动环境。

### 5. 启动后端

保持当前目录为 `ai-creativity-s`：

```powershell
.\mvnw.cmd -DskipTests package
.\mvnw.cmd spring-boot:run
```

后端端口 `8123`，接口前缀 `/api`；Knife4j 地址：

```text
http://localhost:8123/api/doc.html
```

必须从后端工程根目录启动：生成目录以 `user.dir` 为基准。切换目录运行 JAR 会改变预览与发布文件位置。

### 6. 启动前端

另开 PowerShell 窗口：

```powershell
Set-Location D:/Code_Program/Program/ai-no-code/ai-creativity-s/yu-ai-code-mother-frontend
npm ci
npm run dev
```

访问 Vite 输出的实际地址，通常是 `http://localhost:5173`。

已提交的 `.env.development` 仅含公开地址：`VITE_API_BASE_URL=/api`、`VITE_DEPLOY_DOMAIN=http://localhost`。Vite 将 `/api` 代理至 `http://localhost:8123`。登录请求与 SSE 使用 Cookie，请统一使用 `localhost`，不要混用 `127.0.0.1` 造成会话不一致。

### 7. 走通生成链路

1. 注册、登录，在首页输入明确的网站需求并创建应用。
2. 后端调用路由模型决定生成方式，进入应用对话页面。
3. 观察 SSE 文本及工具调用信息，等待代码落盘。
4. HTML / 多文件生成完成后直接预览；Vue 工程还需等待 npm 安装和构建。
5. 继续输入修改要求，或选中页面元素后描述修改。
6. 下载源码 ZIP；如需通过发布 URL 访问，继续配置下节 Nginx。

生成目录按 `html_<appId>`、`multi_file_<appId>`、`vue_project_<appId>` 区分。Vue 流结束后的构建为异步操作，前端结束提示不等于构建已完成，暂时无法预览时先检查后端构建日志和 `dist`。

## 静态发布与 Nginx

这里的“一键部署”是将生成结果复制到本机 `tmp/code_deploy/<deployKey>`，再交给 Nginx 托管，不是自动创建云服务器或发布到云平台。

### 1. 构建管理前端

在前端工程目录执行：

```powershell
$env:VITE_API_BASE_URL = '/api'
$env:VITE_DEPLOY_DOMAIN = 'http://localhost'
npm run build
```

生产构建必须明确设置 API 地址；否则源码 fallback 可能指向访问者自己的 `localhost:8123`。`VITE_` 变量会写入浏览器资源，**绝不能放模型 Key**，修改后必须重新构建。

### 2. 配置并启动 Nginx

修改 `ai-creativity-s/nginx.conf` 中两处前端 `root` 和部署目录 `alias`，使其指向自己的绝对路径。示例默认监听 `80`，API 转发到 `8123`，已关闭 SSE 代理缓冲。

从 Nginx 安装目录运行，以下安装路径仅为示例：

```powershell
.\nginx.exe -p 'D:/Code_Tools/Nginx/nginx-1.28.0/' -c 'D:/Code_Program/Program/ai-no-code/ai-creativity-s/nginx.conf' -t
.\nginx.exe -p 'D:/Code_Tools/Nginx/nginx-1.28.0/' -c 'D:/Code_Program/Program/ai-no-code/ai-creativity-s/nginx.conf'
```

先确认 `-t` 成功再启动；如已有 Nginx 实例，使用其对应配置 reload，不要重复启动。访问 `http://localhost`，在应用页点击部署，再验证返回的六位部署标识 URL 与静态资源。

外网部署还需修改 `AppConstant.DEPLOY_HOST`、前端 `VITE_DEPLOY_DOMAIN`、Nginx `server_name`、实际路径及 HTTPS 配置。不要只修改前端域名，后端仍会返回旧地址。

### 3. 运行打包后的后端

在后端工程根目录、已配置环境变量的终端运行：

```powershell
java -jar target/yu-ai-code-mother-0.0.1-SNAPSHOT.jar
```

后端服务器也必须安装 Node / npm，允许访问依赖仓库，并具备生成目录的读写权限。

## 验证与常见问题

```powershell
# 后端编译、打包，不调用真实模型测试
.\mvnw.cmd -DskipTests package

# 以下在前端目录执行
npm run type-check
npm run build
```

部分测试使用 `@SpringBootTest` 并调用真实模型，需要 MySQL、Redis、模型密钥和配额；不要把它们当作无需外部服务的单元测试直接批量运行。

| 现象 | 检查方向 |
| --- | --- |
| 配置占位符解析失败 | 确认在同一终端或 IDE 中设置 DB_PASSWORD、AI_API_KEY |
| Redis 连接 / 登录态异常 | Redis 地址、密码、DB 0；浏览器主机名是否一致 |
| 401 / 403 / 模型不存在 | Key、模型 ID、配额、服务商权限与工具调用支持 |
| SSE 不刷新 | Nginx buffering、超时设置、Cookie 和后端日志 |
| Vue 预览空白 | npm 是否位于后端 PATH、依赖安装日志、dist 是否生成 |
| 发布链接无法访问 | Nginx 是否启动、部署目录 alias、DEPLOY_HOST 是否对应 |
| 发布请求超时 | Vue 构建可能超过前端 Axios 60 秒超时；检查后端实际构建状态 |
| 依赖冲突 | 当前 LangChain4j 主组件与 Redis community 版本不同，需结合 Maven 依赖树排查 |

MyBatis 代码生成器不是启动步骤；需要生成实体时，它也从 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 读取连接信息。生成器会写入代码，运行前确认输出路径。

## 安全与使用边界

- 仓库配置不包含真实 API Key 或数据库密码。已在其他位置暴露过的 Key 应在服务商后台撤销，删除文件不能替代轮换。
- `.gitignore` 排除本地配置、密钥文件、IDE、依赖、构建产物与生成应用；提交前仍需检查暂存差异。
- **文件工具与 npm 构建不是安全沙箱**：现有工具存在绝对路径及目录约束不足的问题；生成依赖的生命周期脚本也会以服务进程权限运行。不要以管理员身份运行，不要直接开放给不可信公网用户。生产使用前应补充容器 / 进程隔离、路径约束与资源配额。
- 输入护轨和限流不能代替鉴权、提示词注入防护或生成内容审核。同一应用并发生成仍需业务层协调。
- 删除应用不等于清理所有生成文件、部署目录和内存缓存；本地演示结束后应自行检查和清理。
- 本文给出基于源码的完整运行路径，不将静态检查视为真实模型生成、Nginx 发布或生产环境联调通过的证明。

## 来源与说明

本项目结合编程导航 / 程序员鱼皮相关项目实践和个人学习笔记完成，保留源码中的原始包名、作者及来源注释。它是用于学习、实践和交流的工程，不宣称所有基础代码均为独立原创。

当前仓库未附明确的统一开源许可证，使用与再分发前请核实原始课程、模板及第三方依赖的授权要求。
