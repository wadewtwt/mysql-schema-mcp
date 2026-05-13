$ErrorActionPreference = 'Stop'

if (-not $env:MYSQL_MCP_HOST) { throw 'MYSQL_MCP_HOST is required.' }
if (-not $env:MYSQL_MCP_PORT) { $env:MYSQL_MCP_PORT = '3306' }
if (-not $env:MYSQL_MCP_DATABASE) { throw 'MYSQL_MCP_DATABASE is required.' }
if (-not $env:MYSQL_MCP_USERNAME) { throw 'MYSQL_MCP_USERNAME is required.' }
if (-not $env:MYSQL_MCP_PASSWORD) { throw 'MYSQL_MCP_PASSWORD is required.' }

$jarPath = 'D:\work\company\chimeta-parent\scripts\mysql-schema-mcp\target\mysql-schema-mcp-0.1.0.jar'

if (-not (Test-Path $jarPath)) {
    throw "Jar not found: $jarPath. Run mvn clean package first."
}

& java -jar $jarPath
