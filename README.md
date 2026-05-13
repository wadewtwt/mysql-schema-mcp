# MySQL 表结构 MCP 服务使用说明（纯中文版）

## 1. 这是什么

这是一个基于标准 MCP 协议的只读 MySQL 表结构服务。  
任何支持 MCP 的 AI 客户端都可以接入，不限于某一个产品。

该服务提供 3 个工具：

- `list_tables`：列出当前数据库中的表
- `describe_table`：查看指定表的字段、主键、默认值、注释、索引
- `get_entity_context`：生成面向 Java Entity/DO 的字段类型上下文

## 3. 前置条件

- 已安装 Java 17 或以上版本
- 已安装 Maven 3.6 或以上版本
- 有可连接的 MySQL 账号（建议只读账号）

## 4. 小白一步一步使用

### 第一步：确认 Java 可用

在终端执行：

```powershell
java -version
```

看到版本信息即可。

### 第二步：构建 Jar

在终端执行：

```powershell
cd 你的本地项目路径\mysql-schema-mcp
mvn clean package
```

构建成功后，`target` 目录会生成：

`mysql-schema-mcp-0.1.0.jar`

### 第三步：先设置本地环境变量（推荐）

推荐不要把数据库账号密码写进 MCP 配置文件。  
建议先在系统环境变量中设置以下 5 个变量：

- `MYSQL_MCP_HOST`
- `MYSQL_MCP_PORT`
- `MYSQL_MCP_DATABASE`
- `MYSQL_MCP_USERNAME`
- `MYSQL_MCP_PASSWORD`

#### Windows（PowerShell）

当前终端临时生效（只对当前窗口有效）：

```powershell
$env:MYSQL_MCP_HOST="127.0.0.1"
$env:MYSQL_MCP_PORT="3306"
$env:MYSQL_MCP_DATABASE="你的数据库名"
$env:MYSQL_MCP_USERNAME="你的用户名"
$env:MYSQL_MCP_PASSWORD="你的密码"
```

用户级持久生效（重开终端后仍有效）：

```powershell
[Environment]::SetEnvironmentVariable("MYSQL_MCP_HOST","127.0.0.1","User")
[Environment]::SetEnvironmentVariable("MYSQL_MCP_PORT","3306","User")
[Environment]::SetEnvironmentVariable("MYSQL_MCP_DATABASE","你的数据库名","User")
[Environment]::SetEnvironmentVariable("MYSQL_MCP_USERNAME","你的用户名","User")
[Environment]::SetEnvironmentVariable("MYSQL_MCP_PASSWORD","你的密码","User")
```

校验：

```powershell
echo $env:MYSQL_MCP_HOST
```

#### Linux（bash/zsh）

当前终端临时生效（只对当前窗口有效）：

```bash
export MYSQL_MCP_HOST="127.0.0.1"
export MYSQL_MCP_PORT="3306"
export MYSQL_MCP_DATABASE="你的数据库名"
export MYSQL_MCP_USERNAME="你的用户名"
export MYSQL_MCP_PASSWORD="你的密码"
```

用户级持久生效（写入 `~/.bashrc`，zsh 可写 `~/.zshrc`）：

```bash
echo 'export MYSQL_MCP_HOST="127.0.0.1"' >> ~/.bashrc
echo 'export MYSQL_MCP_PORT="3306"' >> ~/.bashrc
echo 'export MYSQL_MCP_DATABASE="你的数据库名"' >> ~/.bashrc
echo 'export MYSQL_MCP_USERNAME="你的用户名"' >> ~/.bashrc
echo 'export MYSQL_MCP_PASSWORD="你的密码"' >> ~/.bashrc
source ~/.bashrc
```

校验：

```bash
echo $MYSQL_MCP_HOST
```

### 第四步：在 AI 客户端中配置 MCP

你的 AI 客户端如果支持 MCP，一般都支持配置一个 `stdio` 服务。  
把下面配置加到客户端的 MCP 配置文件中（路径以你的客户端为准）：

```toml
[mcp_servers.mysql_schema]
command = "java"
args = ["-jar", "你的本地项目路径\\mysql-schema-mcp\\target\\mysql-schema-mcp-0.1.0.jar"]
```

说明：

- `command` 固定用 `java`
- `args` 指向这个 Jar 的绝对路径
- MySQL 连接信息从系统环境变量读取（见上一步）

### 第五步：重启 AI 客户端

配置保存后，重启客户端，让 MCP 配置生效。

### 第六步：验证是否成功

在 AI 对话里让它调用下面工具：

- `list_tables`
- `describe_table`（传一个真实表名）
- `get_entity_context`（传一个真实表名）

能返回表结构信息就说明接入成功。

## 5. `start-mysql-schema-mcp.ps1` 是干什么的

文件位置：

`你的本地项目路径\mysql-schema-mcp\start-mysql-schema-mcp.ps1`

作用是“便捷启动 + 检查”：

- 检查必需环境变量是否存在
- 检查 Jar 是否存在
- 最后执行 `java -jar ...`

这个脚本不是必须。  
如果你的 AI 客户端已经按上面 `command + args + env` 配置好，客户端会自动拉起服务。

## 6. 常见问题排查

### 问题 1：客户端看不到 `mysql_schema`

排查顺序：

1. MCP 配置文件是否保存成功
2. Jar 路径是否写成绝对路径
3. 客户端是否已重启

### 问题 2：连接数据库失败

排查顺序：

1. `MYSQL_MCP_HOST/PORT/DATABASE/USERNAME/PASSWORD` 是否正确
2. 数据库是否允许当前机器 IP 连接
3. 账号权限是否足够（至少可读 `information_schema`）

### 问题 3：`java` 命令找不到

说明 Java 未正确安装或未加入 PATH。  
重新安装 Java 17+，并重新打开终端再试。

## 7. 给其他项目或同事复用

最简单的交付方式：

1. 把 `mysql-schema-mcp-0.1.0.jar` 发给对方
2. 把本 README 一起发给对方
3. 对方在自己的 AI 客户端里按第 4 节配置

建议：

- 生产环境不要用高权限账号，尽量使用只读账号
- 不要把明文密码提交到 Git 仓库
- 可以给测试库、生产库分别配置不同的 MCP 名称
